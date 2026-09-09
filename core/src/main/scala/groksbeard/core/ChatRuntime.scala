package groksbeard.core

import scala.annotation.unused

import zio.*
import zio.json.*
import zio.json.ast.Json

final class ChatRuntime private (
    host: HostOut,
    transport: AcpTransport,
    review: ReviewOps,
    sessions: SessionRepo,
    mentions: Mentions,
    persist: ChangesPersist,
    copies: TranscriptOut,
    cwd: String,
    capabilities: ClientCapabilities,
    fallbackSessionId: SessionId,
    activeFile: () => Option[PromptChip],
    @unused includeActiveFile: () => Boolean,
    @unused settings: () => SettingsState,
    terminals: Terminals,
    mcps: Mcps,
    scope: Scope,
    gate: Semaphore,
    reentrant: FiberRef[Boolean],
    beforeInitialize: UIO[Unit],
    bag: Ref[ChatState],
    prefs: UiPrefs,
):
  private val framed = Framed(SessionState())
  private val store  = ChangeStore()
  private val empty  = EmptySessionTracker()

  private def exclusive[A](body: UIO[A]): UIO[A] =
    reentrant.get.flatMap { held =>
      if held then body
      else gate.withPermit(reentrant.locally(true)(body))
    }

  private def snap: UIO[ChatState]                       = bag.get
  private def put(s: ChatState): UIO[Unit]               = bag.set(s)
  private def edit(f: ChatState => ChatState): UIO[Unit] = bag.update(f)

  private def post(msg: HostMsg): UIO[Unit] = host.post(msg)

  private def absorb(io: IO[BeardError, Unit]): UIO[Unit] =
    io.catchAll(e => post(HostMsg.Error(e.message)))

  private def postChrome(io: BeardError.Result[UiChrome]): UIO[Unit] =
    io.foldZIO(
      e => post(HostMsg.Error(e.message)) *> prefs.current.flatMap(c => post(c.host)),
      c => post(c.host),
    )

  def state: SessionState = framed.state

  def close: UIO[Unit] = transport.close

  def ingestData(chunk: String): UIO[Unit] = exclusive(ingestChunk(chunk))

  def noteAgentLine(line: String): UIO[Unit] = exclusive {
    AgentLog.classify(line) match
      case Some(msg) => post(HostMsg.Error(msg))
      case None      => ZIO.unit
  }

  def noteAgentGone: UIO[Unit] = exclusive(doAgentGone)

  def ready: UIO[Unit] =
    exclusive {
      snap.flatMap { s =>
        val startInit = !s.initializeSent
        put(s.copy(live = true, initializeSent = true)) *>
          post(HostMsg.Ready) *>
          prefs.hydrate.flatMap(c => post(c.host)) *>
          (if store.list.nonEmpty then emitChanges else ZIO.unit).as(startInit)
      }
    }.flatMap { startInit =>
      if !startInit then ZIO.unit
      else
        beforeInitialize.forkIn(scope) *> exclusive {
          rpc(
            AcpMethod.Initialize,
            InitializeParams(1, capabilities, ClientInfo("groks-beard", ChatRuntime.ProductTitle, "0.2.0")).asJson,
          )
        }
    }

  def restoreChanges(sets: List[ChangeSet]): UIO[Unit] = exclusive {
    ZIO.succeed(store.replace(sets)) *>
      snap.flatMap(s => if s.live && sets.nonEmpty then emitChanges else ZIO.unit)
  }

  def send(text: String, images: List[ImageChip] = Nil): UIO[Unit] = exclusive(doSend(text, images))

  def queue(text: String, images: List[ImageChip] = Nil): UIO[Unit] = exclusive {
    snap.flatMap { s =>
      val trimmed = text.trim
      val chosen  = PromptChip.chipsForSend(s.chips, activeFile(), s.settingsState.includeActiveFileByDefault)
      val pics    = if images.nonEmpty then images else s.pendingImages
      if trimmed.isEmpty && chosen.isEmpty && pics.isEmpty then ZIO.unit
      else enqueue(trimmed, chosen, pics)
    }
  }

  def cancelTurn(keepChildren: Boolean): UIO[Unit] = exclusive(doCancelTurn(keepChildren))

  def attachChild(id: TaskId): UIO[Unit] = exclusive {
    snap.flatMap { s =>
      val sid   = SessionId(id.value)
      val turns = s.childTurns.getOrElse(sid.value, Nil)
      post(HostMsg.ChildTranscript(sid, turns))
    }
  }

  def steerChild(id: TaskId, text: String, queue: Boolean): UIO[Unit] = exclusive {
    val sid  = SessionId(id.value)
    val body = text.trim
    if body.isEmpty then ZIO.unit
    else
      edit(_.copy(promptSid = Some(sid))) *> {
        val params = SessionPromptParams(sid, List(PromptBlock.Text(body))).asJson
        val tagged =
          if queue then
            params match
              case obj: Json.Obj => Json.Obj(obj.fields :+ ("_meta" -> Json.Obj("queue" -> Json.Bool(true)))*)
              case other         => other
          else params
        rpc(AcpMethod.SessionPrompt, tagged)
      }
    end if
  }

  def viewPlan: UIO[Unit] = exclusive {
    snap.flatMap { s =>
      val id = s.sessionId.getOrElse(SessionId.empty)
      sessions
        .planMarkdown(id)
        .foldZIO(
          e => post(HostMsg.Error(e.message)),
          md => post(HostMsg.PlanView(if md.trim.isEmpty then "No plan written yet" else md)),
        )
    }
  }

  def openAgents: UIO[Unit] = exclusive {
    sessions.agents
      .flatMap(a => sessions.personas.map(p => (a, p)))
      .foldZIO(
        e => post(HostMsg.Error(e.message)),
        (a, p) => post(HostMsg.Agents(a, p)),
      )
  }

  def openDashboard: UIO[Unit] = exclusive(doDashboard)

  def openWorkflows: UIO[Unit] = exclusive(post(HostMsg.Workflows(Nil)))

  def openDoctor: UIO[Unit] = exclusive(doDoctor)

  def btw(text: String): UIO[Unit] = exclusive(doBtw(text))

  def setTheme(id: String): UIO[Unit] = exclusive(postChrome(prefs.setTheme(id)))

  def toggleCompact: UIO[Unit] = exclusive(postChrome(prefs.toggleCompact))

  def toggleVim: UIO[Unit] = exclusive(postChrome(prefs.toggleVim))

  def addImage(mime: String, data: String, name: String): UIO[Unit] = exclusive {
    edit(_.addImage(mime, data, name))
  }

  def removeImage(id: String): UIO[Unit] = exclusive {
    edit(s => s.copy(pendingImages = s.pendingImages.filterNot(_.id == id)))
  }

  def persistConfig(table: String, key: String, value: String): UIO[Unit] = exclusive {
    prefs
      .patch(table, key, value)
      .foldZIO(
        e => post(HostMsg.Error(e.message)),
        c => if table == "ui" then post(c.host) else ZIO.unit,
      )
  }

  def resumeSession(id: SessionId, restoreCode: Boolean = false): UIO[Unit] = exclusive {
    edit(_.copy(restoreCodeNext = restoreCode)) *> doResume(id)
  }

  def sendNow(id: QueueId): UIO[Unit] = exclusive(doSendNow(id))

  def dropQueued(id: QueueId): UIO[Unit] = exclusive {
    edit(s => s.copy(pendingQueue = s.pendingQueue.filterNot(_.id == id))) *> postQueue
  }

  def cancel: UIO[Unit] = exclusive(doCancel)

  def addChip(chip: PromptChip): UIO[Unit] = exclusive(doAddChip(chip))

  def removeChip(absPath: String, startLine: Option[Int], endLine: Option[Int]): UIO[Unit] = exclusive {
    edit { s =>
      s.copy(chips = s.chips.filterNot { c =>
        c.absPath == absPath && c.startLine == startLine && c.endLine == endLine
      })
    }
  }

  def currentSettings: UIO[SettingsState] = snap.map(_.settingsState)

  def replaceSettings(next: SettingsState): UIO[Unit] = exclusive {
    edit(_.copy(settingsState = next)) *> post(HostMsg.settings(next))
  }

  def setSetting(key: String, value: String | Boolean): UIO[Unit] = exclusive {
    snap.flatMap { s =>
      val next = ChatRuntime.patchSettings(s.settingsState, key, value)
      put(s.copy(settingsState = next)) *> post(HostMsg.settings(next))
    }
  }

  def mentionQuery(query: String): UIO[Unit] = exclusive {
    mentions
      .search(query)
      .foldZIO(
        e => post(HostMsg.Error(e.message)),
        files => post(HostMsg.MentionResults(query, files)),
      )
  }

  def mentionPick(path: String, absPath: String): UIO[Unit] = exclusive {
    edit(s => s.copy(chips = PromptChip.upsert(s.chips, PromptChip(path, absPath, source = ChipSource.Mention))))
  }

  def permissionChoice(requestId: RequestId, optionId: String): UIO[Unit] = exclusive {
    respond(
      requestId,
      Json.Obj(
        "outcome" -> Json.Obj("outcome" -> Json.Str("selected"), "optionId" -> Json.Str(optionId))
      ),
    )
  }

  def planVerdict(requestId: RequestId, verdict: PlanOutcome): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("outcome" -> Json.Str(PlanOutcome.wire(verdict))))
  }

  def questionSubmit(requestId: RequestId, answers: List[QuestionAnswer]): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("answers" -> answers.asJson))
  }

  def questionDismiss(requestId: RequestId): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("answers" -> Json.Arr()))
  }

  def elicitAccept(requestId: RequestId): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("action" -> Json.Str("accept")))
  }

  def elicitDecline(requestId: RequestId): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("action" -> Json.Str("decline")))
  }

  def setMode(id: ModeId): UIO[Unit] = exclusive(doSetMode(id))

  def cycleMode: UIO[Unit] = exclusive {
    snap.flatMap(s => doSetMode(ModeLabel.nextMode(s.modeId, s.modes)))
  }

  def setModel(id: ModelId, requested: Option[String] = None): UIO[Unit] = exclusive {
    if id.isEmpty then ZIO.unit else doSetModel(id, requested)
  }

  def setEffort(level: String): UIO[Unit] = exclusive(doSetEffort(level))

  def openDiff(requestId: RequestId): UIO[Unit] = exclusive {
    snap.flatMap { s =>
      s.pendingPerm.get(requestId) match
        case Some(params) =>
          reconstruct(DiffContent.toolCallFromPermission(params), diskIsBefore = true).flatMap { diffs =>
            showDiffs(ChangeSet.turnTitle(s.currentTitle), diffs)
          }
        case None =>
          store.pending.find(f => f.toolCallId.value == requestId.value || f.path == requestId.value) match
            case Some(file) => showFile(file)
            case None       => openChangesUnlocked
    }
  }

  def openFile(path: String, line: Option[Int]): UIO[Unit] = exclusive {
    val trimmed = path.trim
    if trimmed.isEmpty then ZIO.unit else review.follow(trimmed, line)
  }

  def openChanges: UIO[Unit] = exclusive(openChangesUnlocked)

  def keep(path: String): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      store.keep(path)
      postChanges *> (if store.get(path).isEmpty then post(HostMsg.ClearDiff) else ZIO.unit)
    }
  }

  def keepTurn(turnId: TurnId): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      store.keepTurn(turnId)
      postChanges *> post(HostMsg.ClearDiff)
    }
  }

  def keepAll: UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      store.keepAll()
      postChanges *> post(HostMsg.ClearDiff)
    }
  }

  def undo(path: String): UIO[Unit] = exclusive {
    store.get(path) match
      case None       => ZIO.unit
      case Some(file) => undoFile(file).unit
  }

  def undoTurn(turnId: TurnId): UIO[Unit] = exclusive(undoFiles(store.filesOf(turnId)))

  def undoAll: UIO[Unit] = exclusive(undoFiles(store.pending))

  def closeDiff: UIO[Unit] = exclusive(post(HostMsg.ClearDiff))

  def pendingChanges: List[FileChange] = store.pending

  def pendingSets: List[ChangeSet] = store.list

  def slashPick(name: String): UIO[Unit] = exclusive {
    if SessionCommands.isNew(name) then doNewSession
    else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then doPostList(open = true)
    else if SessionCommands.isRewind(name) then doOpenRewind
    else if SessionCommands.isMcps(name) then doListMcps
    else if SessionCommands.isSessionInfo(name) || SessionCommands.isContext(name) then ZIO.unit
    else if SessionCommands.isTasks(name) then ZIO.unit
    else if SessionCommands.isLoop(name) then post(HostMsg.Error(Tasks.LoopUsage))
    else if SessionCommands.isFork(name) then doFork(ForkArgs(None, None))
    else if SessionCommands.isViewPlan(name) then viewPlan
    else if SessionCommands.isConfigAgents(name) || SessionCommands.isPersonas(name) then openAgents
    else if SessionCommands.isDashboard(name) then doDashboard
    else if SessionCommands.isDoctor(name) then doDoctor
    else if SessionCommands.isTheme(name) then prefs.current.flatMap(c => setTheme(Theme.cycle(c.theme).id))
    else if SessionCommands.isVim(name) then toggleVim
    else if SessionCommands.isCompact(name) then toggleCompact
    else if SessionCommands.isAlwaysApprove(name) then
      snap.flatMap { s =>
        doSetMode(if s.modeId == ModeId.AlwaysApprove then ModeId.Normal else ModeId.AlwaysApprove)
      }
    else ZIO.unit
  }

  def forkSession(worktree: Boolean, directive: String): UIO[Unit] = exclusive {
    doFork(ForkArgs(Some(worktree), Option(directive).filter(_.nonEmpty)))
  }

  def stopTask(id: TaskId): UIO[Unit] = exclusive(doStopTask(id))

  def listMcps: UIO[Unit] = exclusive(doListMcps)

  def setMcpEnabled(name: String, enabled: Boolean): UIO[Unit] = exclusive {
    absorb(mcps.setEnabled(name, enabled).flatMap(rows => post(HostMsg.McpServers(rows))))
  }

  def openRewind: UIO[Unit] = exclusive(doOpenRewind)

  def closeRewind: UIO[Unit] = exclusive(post(HostMsg.RewindList(Nil)))

  def rewindTo(promptIndex: Int): UIO[Unit] = exclusive(doRewindTo(promptIndex))

  def focused: UIO[(Option[SessionId], String)] =
    snap.map(s => (s.sessionId, s.focusedTitle(ChatRuntime.ProductTitle)))

  def renameSession(id: SessionId, op: RenameOp): UIO[Unit] = exclusive(doRename(id, op))

  def deleteSession(id: SessionId): UIO[Unit] = exclusive(doDelete(id))

  def newSession: UIO[Unit] = exclusive(doNewSession)

  def openPicker: UIO[Unit] = exclusive(doPostList(open = true))

  def closePicker: UIO[Unit] = exclusive(doPostList(open = false))

  def copyOut(text: String, path: Option[String], backup: Boolean, conversation: Boolean): UIO[Unit] =
    exclusive {
      absorb {
        copies.deliver(text, path, backup, conversation).flatMap { result =>
          post(HostMsg.Copied(result.message, result.clipboard))
        }
      }
    }

  private def doSend(text: String, images: List[ImageChip] = Nil): UIO[Unit] =
    snap.flatMap { s =>
      val trimmed = text.trim
      SessionCommands.intercept(trimmed) match
        case Some(cmd) if SessionCommands.isNew(cmd.name) =>
          doNewSession
        case Some(cmd) if SessionCommands.isResume(cmd.name) || SessionCommands.isHome(cmd.name) =>
          doPostList(open = true)
        case Some(cmd) if SessionCommands.isModel(cmd.name) =>
          if cmd.args.isEmpty then ZIO.unit
          else
            Effort.splitModelArgs(cmd.args, s.models) match
              case Right((m, e)) => doSetModel(m.modelId, e)
              case Left(err)     => post(HostMsg.Error(err))
        case Some(cmd) if SessionCommands.isEffort(cmd.name) =>
          if cmd.args.isEmpty then ZIO.unit else doSetEffort(cmd.args)
        case Some(cmd) if SessionCommands.isRename(cmd.name) =>
          SessionEdit.parseRename(cmd.args) match
            case Left("empty") => ZIO.unit
            case Left(err)     => post(HostMsg.Error(err))
            case Right(op)     => doRename(s.sessionId.getOrElse(SessionId.empty), op)
        case Some(cmd) if SessionCommands.isDelete(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isHistory(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isCopy(cmd.name) || SessionCommands.isExport(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isRewind(cmd.name) =>
          if cmd.args.isEmpty then doOpenRewind
          else
            cmd.args.trim.toIntOption match
              case Some(i) => doRewindTo(i)
              case None    => post(HostMsg.Error("Usage: /rewind"))
        case Some(cmd) if SessionCommands.isMcps(cmd.name) =>
          doListMcps
        case Some(cmd) if SessionCommands.isSessionInfo(cmd.name) || SessionCommands.isContext(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isTasks(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isLoop(cmd.name) =>
          doLoop(cmd.args)
        case Some(cmd) if SessionCommands.isFork(cmd.name) =>
          Fork.parse(cmd.args) match
            case Left(err)   => post(HostMsg.Error(err))
            case Right(args) => doFork(args)
        case Some(cmd) if SessionCommands.isViewPlan(cmd.name) =>
          viewPlan
        case Some(cmd) if SessionCommands.isBtw(cmd.name) =>
          doBtw(cmd.args)
        case Some(cmd) if SessionCommands.isConfigAgents(cmd.name) || SessionCommands.isPersonas(cmd.name) =>
          openAgents
        case Some(cmd) if SessionCommands.isDashboard(cmd.name) =>
          doDashboard
        case Some(cmd) if SessionCommands.isTheme(cmd.name) =>
          if cmd.args.isEmpty then prefs.current.flatMap(c => setTheme(Theme.cycle(c.theme).id))
          else setTheme(cmd.args)
        case Some(cmd) if SessionCommands.isCompact(cmd.name) =>
          toggleCompact
        case Some(cmd) if SessionCommands.isFullscreen(cmd.name) =>
          postChrome(prefs.setCompact(false))
        case Some(cmd) if SessionCommands.isVim(cmd.name) =>
          toggleVim
        case Some(cmd) if SessionCommands.isDoctor(cmd.name) =>
          doDoctor
        case Some(cmd) if SessionCommands.isAlwaysApprove(cmd.name) =>
          val next = if s.modeId == ModeId.AlwaysApprove then ModeId.Normal else ModeId.AlwaysApprove
          doSetMode(next)
        case Some(cmd) if SessionCommands.isAuto(cmd.name) =>
          val next = if s.modeId == ModeId.Auto then ModeId.Normal else ModeId.Auto
          doSetMode(next)
        case Some(cmd) if SessionCommands.isWorkflowRuns(cmd.name, cmd.args) =>
          post(HostMsg.Workflows(Nil))
        case _ =>
          val chosen = PromptChip.chipsForSend(s.chips, activeFile(), s.settingsState.includeActiveFileByDefault)
          val pics   = if images.nonEmpty then images else s.pendingImages
          if trimmed.isEmpty && chosen.isEmpty && pics.isEmpty then ZIO.unit
          else if s.running || !s.initialized then enqueue(trimmed, chosen, pics)
          else put(s.copy(chips = Nil, pendingImages = Nil)) *> runTurn(trimmed, chosen, pics)
      end match
    }

  private def doListMcps: UIO[Unit] =
    absorb(mcps.list.flatMap(rows => post(HostMsg.McpServers(rows))))

  private def doOpenRewind: UIO[Unit] =
    snap.flatMap { s =>
      if s.running then post(HostMsg.Error("Stop the turn before rewinding."))
      else rpc(AcpMethod.RewindPoints, RewindPointsParams(s.sid(fallbackSessionId)).asJson)
    }

  private def doRewindTo(promptIndex: Int): UIO[Unit] =
    snap.flatMap { s =>
      if s.running then post(HostMsg.Error("Stop the turn before rewinding."))
      else
        put(s.copy(pendingRewind = Some(promptIndex))) *>
          rpc(AcpMethod.RewindExecute, RewindExecuteParams(s.sid(fallbackSessionId), promptIndex).asJson)
    }

  private def doAgentGone: UIO[Unit] =
    bag.modify(_.markAgentGone).flatMap { (already, locked, wasRunning, turn) =>
      if already then ZIO.unit
      else
        val load = locked match
          case None     => ZIO.unit
          case Some(id) => post(HostMsg.SessionLocked(id, SessionLoad.copy(SessionLoadKind.Failed)))
        val turnEnd =
          if !wasRunning then ZIO.unit else post(HostMsg.TurnEnd(turn, StopReason.Cancelled))
        post(HostMsg.Error("Grok agent stopped.")) *> load *> turnEnd
    }

  private def doCancel: UIO[Unit] =
    prefs.current.flatMap(c => doCancelTurn(c.keepChildren.getOrElse(false)))

  private def doCancelTurn(keepChildren: Boolean): UIO[Unit] =
    val kids =
      if keepChildren then ZIO.unit
      else
        snap.flatMap { s =>
          ZIO.foreachDiscard(CancelTurn.liveSubagents(s.tasks)) { row =>
            notify(AcpMethod.SessionCancel, SessionCancelParams(SessionId(row.id.value)).asJson)
          }
        }
    kids *> stopTurn *> drainQueue
  end doCancelTurn

  private def doSendNow(id: QueueId): UIO[Unit] =
    snap.flatMap { s =>
      s.pendingQueue.find(_.id == id) match
        case None       => ZIO.unit
        case Some(item) =>
          put(s.copy(pendingQueue = item +: s.pendingQueue.filterNot(_.id == id))) *>
            stopTurn *> drainQueue
    }

  private def stopTurn: UIO[Unit] =
    snap.flatMap { s =>
      ZIO.foreachDiscard(s.pendingPerm.keys.toList) { id =>
        respond(id, Json.Obj("outcome" -> Json.Obj("outcome" -> Json.Str("cancelled"))))
      }
    } *> snap.flatMap { s =>
      val sid = s.sid(fallbackSessionId)
      notify(AcpMethod.SessionCancel, SessionCancelParams(sid).asJson) *>
        (if s.running then edit(_.copy(running = false)) *> post(HostMsg.TurnEnd(s.currentTurn, StopReason.Cancelled))
         else ZIO.unit)
    }

  private def doAddChip(chip: PromptChip): UIO[Unit] =
    edit(s => s.copy(chips = PromptChip.upsert(s.chips, chip))) *> post(HostMsg.chip(chip))

  private def doSetMode(id: ModeId): UIO[Unit] =
    snap.flatMap { s =>
      rpc(AcpMethod.SessionSetMode, SessionSetModeParams(s.sid(fallbackSessionId), id).asJson) *>
        edit(_.copy(modeId = id)) *>
        ZIO.succeed(framed.state.commitMode(id)) *>
        postMeta
    }

  private def doSetModel(id: ModelId, requested: Option[String]): UIO[Unit] =
    snap.flatMap { s =>
      val target  = s.models.find(_.modelId == id)
      val allowed = Effort.of(target)
      requested match
        case Some(raw) =>
          Effort.pick(raw, allowed) match
            case None        => post(HostMsg.Error(Effort.unknown(raw, allowed)))
            case Some(level) => writeModel(id, Some(level.value), level.value)
        case None =>
          val carried = Option(s.effort).filter(_.nonEmpty).filter(e => allowed.exists(_.value == e))
          writeModel(id, carried, carried.getOrElse(Effort.defaultOf(target)))
    }
  end doSetModel

  private def doSetEffort(raw: String): UIO[Unit] =
    snap.flatMap { s =>
      val allowed =
        if s.currentModel.isDefined then Effort.of(s.currentModel)
        else
          val fromCfg = ConfigOption.efforts(s.configOptions)
          if fromCfg.nonEmpty then fromCfg else Nil
      if s.modelId.isEmpty then post(HostMsg.Error(Effort.NoModel))
      else
        Effort.pick(raw, allowed) match
          case None        => post(HostMsg.Error(Effort.unknown(raw, allowed)))
          case Some(level) =>
            if s.configOptions.exists(_.id == ConfigOption.EffortKey) then
              rpc(
                AcpMethod.SessionSetConfig,
                ConfigOption.setParams(s.sid(fallbackSessionId), ConfigOption.EffortKey, level.value),
              ) *> edit(_.copy(effort = level.value)) *> postMeta
            else writeModel(s.modelId, Some(level.value), level.value)
      end if
    }

  private def writeModel(id: ModelId, sendEffort: Option[String], display: String): UIO[Unit] =
    snap.flatMap { s =>
      val sid       = s.sid(fallbackSessionId)
      val viaConfig = s.configOptions.nonEmpty
      val write     =
        if viaConfig then
          val modelRpc =
            if s.configOptions.exists(_.id == ConfigOption.ModelKey) then
              rpc(AcpMethod.SessionSetConfig, ConfigOption.setParams(sid, ConfigOption.ModelKey, id.value))
            else rpc(AcpMethod.SessionSetModel, setModelJson(sid, id, None))
          val effortRpc =
            sendEffort.filter(_.nonEmpty).filter(_ => s.configOptions.exists(_.id == ConfigOption.EffortKey)) match
              case Some(e) =>
                rpc(AcpMethod.SessionSetConfig, ConfigOption.setParams(sid, ConfigOption.EffortKey, e))
              case None => ZIO.unit
          modelRpc *> effortRpc
        else rpc(AcpMethod.SessionSetModel, setModelJson(sid, id, sendEffort))
      write *> edit(_.copy(modelId = id, effort = display)) *> postMeta
    }

  private def setModelJson(sid: SessionId, id: ModelId, sendEffort: Option[String]): Json =
    sendEffort.filter(_.nonEmpty) match
      case Some(e) =>
        Json.Obj(
          "sessionId" -> Json.Str(sid.value),
          "modelId"   -> Json.Str(id.value),
          "_meta"     -> Json.Obj("reasoningEffort" -> Json.Str(e)),
        )
      case None =>
        Json.Obj(
          "sessionId" -> Json.Str(sid.value),
          "modelId"   -> Json.Str(id.value),
        )

  private def doRename(id: SessionId, op: RenameOp): UIO[Unit] =
    snap.flatMap { s =>
      val target = if id.nonEmpty then id else s.sessionId.getOrElse(SessionId.empty)
      if target.isEmpty then post(HostMsg.Error("No session to rename"))
      else
        op match
          case RenameOp.Manual(t) if t.trim.isEmpty =>
            post(HostMsg.Error("Session title cannot be empty"))
          case _ =>
            sessions
              .rename(target, op)
              .foldZIO(
                e => post(HostMsg.Error(e.message)),
                {
                  case None    => post(HostMsg.Error("Could not rename session"))
                  case Some(_) => postMeta *> doPostList(s.listOpen)
                },
              )
      end if
    }

  private def doDelete(id: SessionId): UIO[Unit] =
    snap.flatMap { s =>
      val target = if id.nonEmpty then id else s.sessionId.getOrElse(SessionId.empty)
      if target.isEmpty then post(HostMsg.Error("No session to delete"))
      else
        sessions
          .delete(target)
          .foldZIO(
            e => post(HostMsg.Error(e.message)),
            gone =>
              if !gone then post(HostMsg.Error("Could not delete session"))
              else
                closeSessionRpc(target) *>
                  (if s.sessionId.contains(target) then doNewSession else doPostList(s.listOpen)),
          )
      end if
    }

  private def closeSessionRpc(id: SessionId): UIO[Unit] =
    snap.flatMap { s =>
      if id.isEmpty || !AgentCapabilities.offersSession(s.agentCaps, "close") then ZIO.unit
      else rpc(AcpMethod.SessionClose, SessionCloseParams(id).asJson)
    }

  private def doNewSession: UIO[Unit] =
    snap.flatMap { s =>
      val prev = s.sessionId.filter(_.nonEmpty)
      edit(st => st.bumpEpoch.cancelPendingResume) *> leaveCurrent *> stopLoops *>
        edit(_.resetLocal.copy(sessionId = None)) *>
        post(HostMsg.ClearTranscript) *>
        prev.map(closeSessionRpc).getOrElse(ZIO.unit) *>
        snap.flatMap(st => rpc(AcpMethod.SessionNew, SessionNewParams(st.sessionCwd).asJson))
    }

  private def doResume(id: SessionId): UIO[Unit] =
    snap.flatMap { s =>
      if id.isEmpty then doPostList(open = true)
      else if s.pendingResume.contains(id) && !s.initialized then ZIO.unit
      else if s.sessionId.contains(id) && s.pendingResume.isEmpty && !s.loading then doPostList(open = false)
      else
        val meta =
          if s.restoreCodeNext then Some(Json.Obj("restoreCode" -> Json.Bool(true))) else None
        val initialized = s.initialized
        val cwd         = s.sessionCwd
        edit(st => st.bumpEpoch.cancelPendingResume) *> leaveCurrent *> stopLoops *>
          edit(_.beginResume(id)) *>
          post(HostMsg.ClearTranscript) *> postMeta *> doPostList(open = false) *>
          (if initialized then
             rpc(AcpMethod.SessionLoad, SessionLoadParams(id, cwd, _meta = meta).asJson, loadSessionId = Some(id))
           else ZIO.unit)
    }

  private def openChangesUnlocked: UIO[Unit] =
    val files = store.pending
    if files.isEmpty then ZIO.unit
    else
      val diffs = files.map(f =>
        ReconstructedFileDiff(
          path = f.path,
          oldText = f.oldSnapshot.getOrElse(""),
          newText = f.newSnapshot.getOrElse(""),
          firstChangedLine = 0,
          wholeFile = f.wholeFile,
          kind = f.kind,
          toolCallId = f.toolCallId,
          fromPath = f.fromPath,
        )
      )
      showDiffs("Grok Changes", diffs)
    end if
  end openChangesUnlocked

  private def enqueue(text: String, chosen: List[PromptChip], images: List[ImageChip] = Nil): UIO[Unit] =
    edit(_.enqueue(text, chosen, images)) *> postQueue

  private def drainQueue: UIO[Unit] =
    bag.modify(_.dequeue).flatMap {
      case None       => postQueue
      case Some(item) => postQueue *> runTurn(item.text, item.chips, item.images)
    }

  private def postQueue: UIO[Unit] =
    snap.flatMap(s => post(HostMsg.Queued(s.pendingQueue.toList)))

  private def runTurn(text: String, chosen: List[PromptChip], images: List[ImageChip] = Nil): UIO[Unit] =
    snap.flatMap { s0 =>
      val next   = s0.startTurn(text, chosen, fallbackSessionId)
      val sid    = next.promptSid.getOrElse(fallbackSessionId)
      val blocks =
        PromptBlock.of(text, chosen, AgentCapabilities.embedded(next.agentCaps)) ++
          ImageAttach.blocks(
            images,
            AgentCapabilities.image(next.agentCaps),
            AgentCapabilities.embedded(next.agentCaps),
          )
      val prompt = if blocks.nonEmpty then blocks else List(PromptBlock.Text(text))
      next.sessionId.foreach(empty.markHasHistory)
      put(next) *>
        post(HostMsg.UserMessage(next.currentTurn, text, chosen)) *>
        rpc(AcpMethod.SessionPrompt, SessionPromptParams(sid, prompt).asJson)
    }

  private def leaveCurrent: UIO[Unit] =
    snap.flatMap { s =>
      s.sessionId match
        case None     => ZIO.unit
        case Some(id) =>
          val del = if empty.shouldDelete(id) then sessions.scheduleEmptyDelete(id) else ZIO.unit
          empty.forget(id)
          del
    }

  private def doPostList(open: Boolean): UIO[Unit] =
    snap.flatMap { s =>
      val sid  = s.sessionId.getOrElse(SessionId.empty)
      val skip = empty.shouldDelete(sid)
      sessions.list
        .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
        .zip(Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        .flatMap { (rows, now) =>
          val listed = SessionIndex.touchCurrent(rows, sid, now, skip)
          edit(_.copy(listOpen = open, listed = listed)) *>
            post(HostMsg.SessionList(listed, sid, openPicker = open))
        }
    }

  private def respond(requestId: RequestId, result: Json): UIO[Unit] =
    bag.modify(_.takeInbound(requestId)).flatMap {
      case None     => ZIO.unit
      case Some(id) => absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.ok(id, result)))))
    }

  private def reject(requestId: RequestId, message: String): UIO[Unit] =
    bag.modify(_.takeInbound(requestId)).flatMap {
      case None     => ZIO.unit
      case Some(id) => absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.fail(id, Rpc.InvalidParams, message)))))
    }

  private def terminalIdOf(params: Json): Option[TerminalId] =
    jsonStr(params, "terminalId").flatMap(TerminalId.fromWire)

  private def handleTerminalCreate(requestId: RequestId, params: Json): UIO[Unit] =
    params.as[TerminalCreateParams] match
      case Left(err) => reject(requestId, s"invalid terminal/create params: $err")
      case Right(p)  =>
        if framed.state.planActive && !PlanTerminals.allowed(p.command, p.args) then
          reject(requestId, PlanTerminals.Reject)
        else
          terminals
            .create(p.command, p.args, p.cwd, p.env, p.outputByteLimit)
            .foldZIO(
              e => reject(requestId, e.message),
              id => bindTerminal(requestId, id, p),
            )

  private def bindTerminal(requestId: RequestId, id: TerminalId, p: TerminalCreateParams): UIO[Unit] =
    snap.flatMap { s =>
      val toolId = s.liveExecute.getOrElse(ToolCallId(id.value))
      val start  =
        if s.liveExecute.isDefined then ZIO.unit
        else
          val cmd =
            if p.args.isEmpty then p.command
            else s"${p.command} ${p.args.mkString(" ")}"
          edit(_.copy(liveExecute = Some(toolId))) *>
            post(
              HostMsg.ToolCall(
                s.currentTurn,
                ToolRow(
                  toolId,
                  "run_terminal_command",
                  ToolKind.Execute,
                  ToolStatus.InProgress,
                  input = Some(cmd).filter(_.nonEmpty),
                ),
              )
            )
      start *> respond(requestId, TerminalCreateResult(id).asJson) *> watchTerminal(id, toolId)
    }

  private def watchTerminal(id: TerminalId, toolId: ToolCallId): UIO[Unit] =
    snap.flatMap { s =>
      val turn = s.currentTurn
      terminals.stream(id).flatMap {
        case None         => ZIO.unit
        case Some(chunks) =>
          chunks
            .filter(_.nonEmpty)
            .foreach(text => exclusive(post(HostMsg.ToolChunk(turn, toolId, text))))
            .forkIn(scope)
            .unit
      }
    }

  private def handleTerminalOutput(requestId: RequestId, params: Json): UIO[Unit] =
    terminalIdOf(params) match
      case None     => reject(requestId, "missing terminalId")
      case Some(id) =>
        terminals.output(id).flatMap {
          case None      => reject(requestId, "unknown terminal")
          case Some(out) => respond(requestId, out.asJson)
        }

  private def handleTerminalWait(requestId: RequestId, params: Json): UIO[Unit] =
    terminalIdOf(params) match
      case None     => reject(requestId, "missing terminalId")
      case Some(id) =>
        terminals
          .waitForExit(id)
          .flatMap { st =>
            exclusive {
              st match
                case None    => reject(requestId, "unknown terminal")
                case Some(e) => respond(requestId, e.asJson)
            }
          }
          .forkIn(scope)
          .unit

  private def handleTerminalKill(requestId: RequestId, params: Json): UIO[Unit] =
    terminalIdOf(params) match
      case None     => reject(requestId, "missing terminalId")
      case Some(id) =>
        terminals.kill(id).flatMap {
          case false => reject(requestId, "unknown terminal")
          case true  => respond(requestId, EmptyObject().asJson)
        }

  private def handleTerminalRelease(requestId: RequestId, params: Json): UIO[Unit] =
    terminalIdOf(params) match
      case None     => reject(requestId, "missing terminalId")
      case Some(id) =>
        terminals.release(id).flatMap {
          case false => reject(requestId, "unknown terminal")
          case true  => respond(requestId, EmptyObject().asJson)
        }

  private def requestKey(id: RpcId): RequestId =
    id match
      case RpcId.Str(s) => RequestId(s)
      case RpcId.Num(n) => RequestId(n.toString)

  private def foldTasks(p: Json, loading: Boolean): UIO[Boolean] =
    snap.flatMap { s =>
      val current = if loading then s.loadModel.tasks else s.tasks
      Tasks.fold(p, current) match
        case None       => ZIO.succeed(false)
        case Some(next) =>
          if loading then edit(_.copy(loadModel = ChatModel.applyMsg(s.loadModel, HostMsg.Tasks(next)))).as(true)
          else
            val notices = Tasks.notices(current, next)
            put(s.copy(tasks = next)) *> (post(HostMsg.Tasks(next)) *> ZIO.foreachDiscard(notices)(post)).as(true)
    }

  private def doLoop(args: String): UIO[Unit] =
    Tasks.parseLoop(args) match
      case Left(err)   => post(HostMsg.Error(err))
      case Right(spec) =>
        bag.modify(_.mintLoop(spec)).flatMap { (id, tasks) =>
          post(HostMsg.Tasks(tasks)) *> startLoop(id, spec)
        }

  private def doFork(args: ForkArgs): UIO[Unit] =
    snap.flatMap { s =>
      s.sessionId.filter(_.nonEmpty) match
        case None    => post(HostMsg.Error(Fork.NoSession))
        case Some(_) =>
          args.worktree match
            case None if s.canWorktree =>
              post(HostMsg.ForkAsk(args.directive.getOrElse("")))
            case None =>
              runFork(worktree = false, args.directive)
            case Some(true) if !s.canWorktree =>
              post(HostMsg.Error(Fork.MissingWorktree))
            case Some(w) =>
              runFork(w, args.directive)
    }

  private def runFork(worktree: Boolean, directive: Option[String]): UIO[Unit] =
    snap.flatMap { s =>
      s.sessionId.filter(_.nonEmpty) match
        case None      => post(HostMsg.Error(Fork.NoSession))
        case Some(sid) =>
          put(s.copy(pendingForkPrompt = directive)) *>
            rpc(
              AcpMethod.SessionFork,
              ForkSessionParams(
                sourceSessionId = sid,
                sourceCwd = s.sessionCwd,
                newCwd = s.sessionCwd,
                sessionKind = Some(if worktree then "worktree" else "fork"),
                sourceWorkspaceDir = if worktree then Some(cwd) else None,
              ).asJson,
            )
    }

  private def ingestFork(result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    error match
      case Some(err) if err.code == Rpc.MethodNotFound =>
        edit(_.copy(pendingForkPrompt = None)) *> post(HostMsg.Error(Fork.MissingCli))
      case Some(err) =>
        edit(_.copy(pendingForkPrompt = None)) *> post(HostMsg.Error(err.message))
      case None =>
        result.flatMap(_.as[ForkSessionResult].toOption) match
          case None =>
            edit(_.copy(pendingForkPrompt = None)) *> post(HostMsg.Error("Fork failed"))
          case Some(forked) =>
            (if forked.newCwd.nonEmpty then edit(_.copy(sessionCwd = forked.newCwd)) else ZIO.unit) *>
              doResume(forked.newSessionId)

  private def sendForkPrompt: UIO[Unit] =
    bag
      .modify { s =>
        (s.pendingForkPrompt, s.copy(pendingForkPrompt = None))
      }
      .flatMap {
        case None       => ZIO.unit
        case Some(text) => if text.isEmpty then ZIO.unit else runTurn(text, Nil)
      }

  private def startLoop(id: TaskId, spec: LoopSpec): UIO[Unit] =
    def fire: UIO[Unit] =
      exclusive(
        snap.flatMap { s =>
          if s.running then enqueue(spec.prompt, Nil)
          else runTurn(spec.prompt, Nil)
        }
      )
    for
      _     <- fire
      fiber <- (ZIO.sleep(spec.interval) *> fire).forever.unit.forkIn(scope)
      _     <- edit(s => s.copy(loopFibers = s.loopFibers.updated(id.value, fiber)))
    yield ()
  end startLoop

  private def doStopTask(id: TaskId): UIO[Unit] =
    snap.flatMap { s =>
      val fiber   = s.loopFibers.get(id.value)
      val row     = s.tasks.find(_.id == id)
      val removed = if row.exists(_.owned) then Tasks.remove(s.tasks, id) else s.tasks
      put(s.copy(loopFibers = s.loopFibers - id.value, tasks = removed)) *> {
        val interrupt = fiber.map(_.interrupt.unit).getOrElse(ZIO.unit)
        val killChild =
          if row.exists(_.kind == TaskKind.Subagent) then
            notify(AcpMethod.SessionCancel, SessionCancelParams(SessionId(id.value)).asJson) *>
              edit { st =>
                row match
                  case Some(r) => st.copy(tasks = Tasks.upsert(st.tasks, r.copy(status = TaskStatus.Cancelled)))
                  case None    => st
              }
          else ZIO.unit
        interrupt *> killChild *> snap.flatMap(st => post(HostMsg.Tasks(st.tasks)))
      }
    }

  private def stopLoops: UIO[Unit] =
    bag
      .modify { s =>
        (s.loopFibers, s.copy(loopFibers = Map.empty))
      }
      .flatMap { fs =>
        ZIO.foreachDiscard(fs.values)(_.interrupt.unit)
      }

  private def notify(method: AcpMethod, params: Json): UIO[Unit] =
    absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.notify(method.value, params)))))

  private def rpc(method: AcpMethod, params: Json, loadSessionId: Option[SessionId] = None): UIO[Unit] =
    bag.modify(_.takeRpc(method, loadSessionId)).flatMap { id =>
      val req = Rpc.Request(id, method.value, params)
      framed.recordOutgoing(req)
      absorb(transport.write(Ndjson.encode(Rpc.toLine(req))))
    }

  private def ingestChunk(chunk: String): UIO[Unit] =
    snap.flatMap { s =>
      val epoch = s.inboundEpoch
      val msgs  = framed.feed(chunk)
      ZIO.foreachDiscard(msgs) { msg =>
        snap.flatMap { now =>
          if now.inboundEpoch == epoch then ingestOne(msg) else ZIO.unit
        }
      }
    }

  private def ingestOne(msg: Rpc): UIO[Unit] =
    msg match
      case Rpc.Notify(method, p) if SessionUpdate.isSessionNotify(method) =>
        snap.flatMap { s =>
          val updateSid = SessionState.decodeNotify(p).map(_.sessionId).filter(_.nonEmpty)
          val childIds  = ChildAttach.idsOf(s.tasks)
          val child     = updateSid.exists(sid => ChildAttach.isChild(sid, s.sessionId, childIds))
          val stale     =
            updateSid.exists(s.cancelledLoads.contains) ||
              (updateSid.exists(sid => s.sessionId.exists(_ != sid)) && !child)
          if stale then ZIO.unit
          else if child then ingestChild(updateSid.get, p)
          else if s.loading then ingestLoading(p) *> ingestUpdate(p)
          else ingestLive(p) *> ingestUpdate(p)
        }
      case Rpc.Request(rid, rawMethod, params) =>
        val reqId = requestKey(rid)
        edit(_.recordInbound(reqId, rid)) *> {
          AcpMethod.parse(rawMethod) match
            case Some(AcpMethod.RequestPermission) =>
              edit(_.parkPermission(reqId, params)) *>
                post(HostMsg.permission(DiffContent.permissionCard(params, reqId)))
            case Some(AcpMethod.ExitPlanMode) =>
              val md =
                jsonStr(params, "planContent").orElse(jsonStr(params, "planMarkdown")).getOrElse("")
              post(HostMsg.plan(PlanCard(reqId, md)))
            case Some(AcpMethod.AskUserQuestion) =>
              val questions =
                params.as[AskUserQuestionParams].toOption.map(_.questions).getOrElse(Nil)
              post(HostMsg.question(QuestionCard(reqId, questions)))
            case Some(AcpMethod.ElicitCreate) | Some(AcpMethod.Elicit) =>
              post(HostMsg.elicit(elicitCard(params, reqId)))
            case Some(AcpMethod.TerminalCreate) =>
              handleTerminalCreate(reqId, params)
            case Some(AcpMethod.TerminalOutput) =>
              handleTerminalOutput(reqId, params)
            case Some(AcpMethod.TerminalWait) =>
              handleTerminalWait(reqId, params)
            case Some(AcpMethod.TerminalKill) =>
              handleTerminalKill(reqId, params)
            case Some(AcpMethod.TerminalRelease) =>
              handleTerminalRelease(reqId, params)
            case _ =>
              edit(_.dropInbound(reqId)) *>
                absorb(
                  transport.write(
                    Ndjson.encode(Rpc.toLine(Rpc.fail(rid, Rpc.MethodNotFound, s"Method not found: $rawMethod")))
                  )
                )
        }
      case Rpc.Response(id, result, error) =>
        ingestResponse(id, result, error)
      case _ => ZIO.unit

  private def ingestLoading(p: Json): UIO[Unit] =
    val bump = SessionState.decodeUpdate(p) match
      case Some(_: AcpUpdate.User) => edit(_.noteUserWhileLoading)
      case _                       => edit(s => if s.loadCleared then s else s.copy(loadCleared = true))
    bump *> foldTasks(p, loading = true) *> snap.flatMap { s =>
      val msgs = SessionUpdate.hostMsgs(p, s.currentTurn)
      edit { st =>
        msgs.foldLeft(st) {
          case (acc, _: HostMsg.AvailableCommands) => acc
          case (acc, other)                        =>
            val withOcc = other match
              case m: HostMsg.SessionMeta => m.occupancy.fold(acc)(o => acc.copy(occupancy = Some(o)))
              case _                      => acc
            withOcc.copy(loadModel = ChatModel.applyMsg(withOcc.loadModel, other))
        }
      } *> ZIO.foreachDiscard(msgs) {
        case HostMsg.AvailableCommands(cmds) =>
          post(HostMsg.AvailableCommands(SessionCommands.merge(cmds)))
        case _ => ZIO.unit
      }
    }
  end ingestLoading

  private def ingestLive(p: Json): UIO[Unit] =
    val cfg = SessionState.decodeUpdate(p) match
      case Some(AcpUpdate.ConfigOptions(opts)) => edit(_.withConfig(opts))
      case _                                   => ZIO.unit
    val wf = WorkflowRuns.fold(p, Nil)
    cfg *> foldTasks(p, loading = false).flatMap { folded =>
      snap.flatMap { s =>
        val msgs = SessionUpdate.hostMsgs(p, s.currentTurn)
        val note =
          if msgs.nonEmpty || folded then ZIO.unit
          else
            SessionState.decodeUpdate(p) match
              case Some(_) => ZIO.unit
              case None    => ZIO.logWarning(s"ignored session/update ${sessionUpdateKind(p)}")
        val wfPost = wf match
          case Some(runs) => post(HostMsg.Workflows(runs))
          case None       => ZIO.unit
        note *> wfPost *> ZIO.foreachDiscard(msgs) { msg =>
          val out = msg match
            case HostMsg.AvailableCommands(cmds) => HostMsg.AvailableCommands(SessionCommands.merge(cmds))
            case other                           => other
          val stamp = out match
            case m: HostMsg.SessionMeta =>
              m.occupancy match
                case Some(o) => edit(_.copy(occupancy = Some(o)))
                case None    => ZIO.unit
            case HostMsg.ToolCall(_, row) => edit(_.noteLiveTool(row))
            case _                        => ZIO.unit
          stamp *> post(out)
        }
      }
    }
  end ingestLive

  private def ingestResponse(id: RpcId, result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    bag.modify(_.popMethod(id)).flatMap { method =>
      val quiet =
        method.exists(m => m == AcpMethod.SessionLoad || m == AcpMethod.RewindPoints || m == AcpMethod.SessionFork)
      val errPost =
        if quiet then ZIO.unit
        else error.map(e => post(HostMsg.Error(e.message))).getOrElse(ZIO.unit)
      errPost *> (method match
        case Some(AcpMethod.Initialize) =>
          ingestInitialize(result)
        case Some(AcpMethod.SessionSetConfig) =>
          (result match
            case Some(json) => edit(_.withConfig(ConfigOption.of(json)))
            case None       => ZIO.unit
          ) *> postMeta
        case Some(AcpMethod.SessionList) =>
          snap.flatMap { s =>
            val rows = result.map(j => DashRoster.fromList(j, s.sessionCwd)).getOrElse(Nil)
            post(HostMsg.Dashboard(rows))
          }
        case Some(AcpMethod.SessionNew) =>
          ingestSessionNew(result)
        case Some(AcpMethod.SessionLoad) =>
          ingestLoad(id, result, error)
        case Some(AcpMethod.RewindPoints) =>
          if error.isDefined then ZIO.unit
          else post(HostMsg.RewindList(Rewind.decodePoints(result.getOrElse(Json.Null))))
        case Some(AcpMethod.SessionFork) =>
          ingestFork(result, error)
        case Some(AcpMethod.RewindExecute) =>
          ingestRewind(error)
        case Some(AcpMethod.SessionPrompt) =>
          ingestPromptDone(result)
        case _ => ZIO.unit)
    }

  private def ingestInitialize(result: Option[Json]): UIO[Unit] =
    snap.flatMap { s =>
      val caps = result.flatMap(_.as[InitializeResult].toOption).map(_.agentCapabilities).getOrElse(s.agentCaps)
      val next = s.copy(
        initialized = true,
        canWorktree = result.exists(Fork.offersWorktree),
        agentCaps = caps,
        restoreCodeNext = false,
      )
      val meta =
        if s.restoreCodeNext then Some(Json.Obj("restoreCode" -> Json.Bool(true))) else None
      put(next) *> (next.pendingResume match
        case Some(id) =>
          rpc(
            AcpMethod.SessionLoad,
            SessionLoadParams(id, next.sessionCwd, _meta = meta).asJson,
            loadSessionId = Some(id),
          )
        case None => rpc(AcpMethod.SessionNew, SessionNewParams(next.sessionCwd).asJson))
    }

  private def ingestSessionNew(result: Option[Json]): UIO[Unit] =
    result match
      case None       => ZIO.unit
      case Some(json) =>
        json.as[SessionNewResult] match
          case Left(_)        => ZIO.unit
          case Right(decoded) =>
            snap.flatMap { s =>
              empty.markCreated(decoded.sessionId)
              val steal =
                s.pendingResume.nonEmpty || s.sessionId.exists(cur => cur.nonEmpty && cur != decoded.sessionId)
              if steal then
                if empty.shouldDelete(decoded.sessionId) then sessions.scheduleEmptyDelete(decoded.sessionId)
                else ZIO.unit
              else
                decoded.modes.foreach(m => framed.state.commitMode(m.currentModeId))
                val applied = s.withSession(decoded, json)
                val withId  =
                  if applied.sessionId.isEmpty then applied.copy(sessionId = Some(fallbackSessionId)) else applied
                put(withId) *> postMeta *> post(HostMsg.settings(withId.settingsState)) *> doPostList(open = false) *>
                  (if withId.pendingQueue.nonEmpty then drainQueue else ZIO.unit)
              end if
            }

  private def ingestRewind(error: Option[RpcError]): UIO[Unit] =
    bag
      .modify { s =>
        (s.pendingRewind, s.copy(pendingRewind = None, pendingQueue = Vector.empty))
      }
      .flatMap { idx =>
        if error.isDefined then ZIO.unit
        else
          idx match
            case None    => ZIO.unit
            case Some(i) =>
              store.keepAll()
              postQueue *> postChanges *> post(HostMsg.ClearDiff) *> post(HostMsg.Rewound(i))
      }

  private def ingestPromptDone(result: Option[Json]): UIO[Unit] =
    snap.flatMap { s =>
      val reason =
        result.flatMap(_.as[SessionPromptResult].toOption).map(_.stopReason).getOrElse(StopReason.EndTurn)
      val target  = s.promptSid
      val cleared = s.copy(promptSid = None)
      if target.exists(sid => s.sessionId.contains(sid) || s.sessionId.isEmpty) || target.isEmpty then
        put(cleared.copy(running = false)) *>
          post(HostMsg.TurnEnd(s.currentTurn, reason)) *> drainQueue
      else
        val sid            = target.get
        val (next, folded) = cleared.foldChild(sid, HostMsg.TurnEnd(TurnId(s"child-${sid.value}"), reason))
        put(folded) *> post(HostMsg.ChildTranscript(sid, next))
    }

  private def ingestLoad(id: RpcId, result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    snap.flatMap { s0 =>
      val (loadSid, popped) = s0.popLoad(id)
      val stale             =
        loadSid.exists(popped.cancelledLoads.contains) ||
          popped.pendingResume.exists(want => loadSid.exists(_ != want)) ||
          popped.pendingResume.isEmpty
      if stale then put(popped)
      else
        val wanted = popped.pendingResume
        val base   = popped.copy(loading = false, pendingResume = None)
        error match
          case Some(err) =>
            val kind = SessionLoad.classify(err.message, Some(err.code.toString))
            val sid  = wanted.getOrElse(SessionId.empty)
            put(base.copy(pendingForkPrompt = None)) *>
              post(HostMsg.SessionLocked(sid, SessionLoad.copy(kind))) *>
              (if kind == SessionLoadKind.Failed then post(HostMsg.Error(err.message)) else ZIO.unit)
          case None =>
            val clear       = if !base.loadCleared then post(HostMsg.ClearTranscript) else ZIO.unit
            val snapTurns   = ChatModel.snapshotTurns(base.loadModel.turns)
            val todos       = base.loadModel.todos
            val loadedTasks = base.loadModel.tasks
            val sid         = wanted.getOrElse(SessionId.empty)
            val withTasks   = base.copy(loadModel = ChatModel.empty, tasks = loadedTasks)
            val applied     =
              result match
                case Some(json) =>
                  json.as[SessionNewResult] match
                    case Right(decoded) =>
                      decoded.modes.foreach(m => framed.state.commitMode(m.currentModeId))
                      withTasks.withSession(decoded, json)
                    case Left(_) => withTasks.withConfig(result.map(ConfigOption.of).getOrElse(Nil))
                case None => withTasks
            val withId =
              wanted match
                case Some(loadId) =>
                  val sid2 = if applied.sessionId.isEmpty then applied.copy(sessionId = Some(loadId)) else applied
                  empty.markHasHistory(sid2.sessionId.getOrElse(loadId))
                  sid2
                case None => applied
            put(withId) *>
              clear *>
              post(HostMsg.Transcript(snapTurns)) *>
              postLoadedTodos(sid, todos) *>
              (if loadedTasks.isEmpty then ZIO.unit else post(HostMsg.Tasks(loadedTasks))) *>
              postMeta *> post(HostMsg.settings(withId.settingsState)) *> doPostList(open = false) *>
              (if withId.pendingQueue.nonEmpty then drainQueue else ZIO.unit) *> sendForkPrompt
        end match
      end if
    }

  private def ingestUpdate(params: Json): UIO[Unit] =
    SessionState.decodeUpdate(params) match
      case Some(call: AcpUpdate.ToolCall)       => ingestTool(call.status, toBody(call))
      case Some(call: AcpUpdate.ToolCallUpdate) => ingestTool(call.status, toBody(call))
      case _                                    => ZIO.unit

  private def ingestTool(status: ToolStatus, body: AcpToolCall): UIO[Unit] =
    reconstruct(body.asJson, DiffContent.diskIsBefore(status)).flatMap { diffs =>
      if diffs.isEmpty then ZIO.unit
      else
        snap.flatMap { s =>
          store.ingest(
            s.sid(fallbackSessionId),
            s.currentTurn,
            s.currentTitle,
            diffs.map(DiffContent.fileChangeFrom),
          )
          postChanges
        }
    }

  private def toBody(call: AcpUpdate.ToolCall): AcpToolCall =
    AcpToolCall(
      call.toolCallId,
      call.title,
      call.kind,
      call.status,
      call.content,
      call.rawInput,
      call.locations,
      call.rawOutput,
    )

  private def toBody(call: AcpUpdate.ToolCallUpdate): AcpToolCall =
    AcpToolCall(
      call.toolCallId,
      call.title,
      call.kind,
      call.status,
      call.content,
      call.rawInput,
      call.locations,
      call.rawOutput,
    )

  private def undoFile(file: FileChange): UIO[Boolean] =
    review
      .readDisk(file.path)
      .zip(review.confirmDirty(file.path))
      .foldZIO(
        e => post(HostMsg.Error(e.message)).as(false),
        (disk, dirty) =>
          ChangeSet.resolveUndo(file, disk, dirty) match
            case UndoResolution.Apply(mutations) =>
              review
                .applyUndo(mutations)
                .foldZIO(
                  e => post(HostMsg.Error(e.message)).as(false),
                  _ =>
                    ZIO.succeed {
                      store.drop(file.path)
                      true
                    },
                )
            case UndoResolution.Disabled(reason) =>
              post(HostMsg.Error(s"Undo unavailable: $reason")).as(false)
            case UndoResolution.Cancelled =>
              ZIO.succeed(false),
      )

  private def undoFiles(files: List[FileChange]): UIO[Unit] =
    def loop(rest: List[FileChange]): UIO[Unit] =
      rest match
        case Nil    => ZIO.unit
        case h :: t => undoFile(h).flatMap(ok => if ok then loop(t) else ZIO.unit)
    loop(files) *> postChanges *> post(HostMsg.ClearDiff)

  private def emitChanges: UIO[Unit] =
    post(HostMsg.changes(store.summary)) *> review.onStoreChange

  private def postChanges: UIO[Unit] =
    val sets = store.list
    emitChanges *> absorb(persist.save(sets))

  private def reconstruct(toolCall: Json, diskIsBefore: Boolean): UIO[List[ReconstructedFileDiff]] =
    val extracted = DiffContent.diffsFromToolCall(toolCall)
    val paths     = extracted.diffs.map(_.path).distinct
    ZIO
      .foreach(paths)(p => review.readDisk(p).map(p -> _))
      .map { pairs =>
        val snaps = pairs.toMap
        DiffContent.reconstruct(toolCall, p => snaps.getOrElse(p, None), diskIsBefore)
      }
      .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
  end reconstruct

  private def showFile(file: FileChange): UIO[Unit] =
    val oldText = file.oldSnapshot.getOrElse("")
    val newText = file.newSnapshot.getOrElse("")
    post(HostMsg.DiffPreview(file.path, oldText, newText, file.wholeFile)) *>
      review.openNativeDiffs(
        UnifiedDiff.fileName(file.path),
        List(DiffPair(file.path, oldText, newText, file.wholeFile)),
      )

  private def showDiffs(heading: String, diffs: List[ReconstructedFileDiff]): UIO[Unit] =
    val preview = diffs.headOption match
      case None    => ZIO.unit
      case Some(d) => post(HostMsg.DiffPreview(d.path, d.oldText, d.newText, d.wholeFile))
    val pairs = diffs.map(d => DiffPair(d.path, d.oldText, d.newText, d.wholeFile))
    preview *> (if pairs.isEmpty then ZIO.unit else review.openNativeDiffs(heading, pairs))

  private def postLoadedTodos(sessionId: SessionId, fromAcp: List[TodoEntry]): UIO[Unit] =
    if fromAcp.nonEmpty then post(HostMsg.Todos(fromAcp))
    else
      sessions
        .plan(sessionId)
        .catchAll(_ => ZIO.succeed(Nil))
        .flatMap { disk =>
          if disk.isEmpty then ZIO.unit else post(HostMsg.Todos(disk))
        }

  private def postMeta: UIO[Unit] =
    snap.flatMap { s =>
      val sid = s.sessionId.getOrElse(SessionId.empty)
      sessions.list
        .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
        .flatMap { rows =>
          val named =
            rows
              .find(_.id == sid)
              .map(SessionIndex.displayTitle)
              .filter(_.nonEmpty)
              .getOrElse(ChatRuntime.ProductTitle)
          post(HostMsg.SessionMeta(sid, named, s.modeId, s.modes, s.occupancy, s.modelId, s.models, s.effort, cwd))
        }
    }

  private def sessionUpdateKind(params: Json): String =
    def field(obj: Json.Obj, key: String): Option[Json] =
      obj.fields.collectFirst { case (k, v) if k == key => v }
    params match
      case obj: Json.Obj =>
        field(obj, "update")
          .collect { case inner: Json.Obj => inner }
          .flatMap(u => field(u, "sessionUpdate"))
          .collect { case Json.Str(s) => s }
          .getOrElse("unknown")
      case _ => "unknown"
  end sessionUpdateKind

  private def jsonStr(json: Json, key: String): Option[String] =
    json match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case (k, Json.Str(s)) if k == key => s }
      case _ => None

  private def elicitCard(params: Json, requestId: RequestId): ElicitCard =
    val mode   = jsonStr(params, "mode").map(ElicitMode.fromWire).getOrElse(ElicitMode.Form)
    val title  = jsonStr(params, "message").orElse(jsonStr(params, "title")).getOrElse("Input required")
    val url    = jsonStr(params, "url")
    val server = jsonStr(params, "serverName").getOrElse("mcp")
    ElicitCard(requestId, server, mode, title, url)

  private def ingestChild(sid: SessionId, p: Json): UIO[Unit] =
    snap.flatMap { s =>
      val msgs           = SessionUpdate.hostMsgs(p, TurnId(s"child-${sid.value}"))
      val (next, folded) = s.foldChildMsgs(sid, msgs)
      WorkflowRuns.fold(p, Nil)
      put(folded) *> post(HostMsg.ChildTranscript(sid, next))
    }

  private def doDashboard: UIO[Unit] =
    snap.flatMap { s =>
      if AgentCapabilities.offersSession(s.agentCaps, "list") then
        rpc(AcpMethod.SessionList, SessionListParams(cwd = Some(s.sessionCwd)).asJson)
      else
        sessions.list.foldZIO(
          e => post(HostMsg.Error(e.message)),
          rows =>
            post(
              HostMsg.Dashboard(
                rows.map(r =>
                  DashRow(
                    r.id,
                    SessionIndex.displayTitle(r),
                    s.sessionCwd,
                    "idle",
                    r.summary.getOrElse(""),
                    r.activityMs,
                  )
                )
              )
            ),
        )
    }

  private def doDoctor: UIO[Unit] =
    snap.flatMap { s =>
      val findings = Doctor.collect(
        cliPath = Some(s.settingsState.cliPath).filter(_.nonEmpty),
        version = None,
        embeddedContext = AgentCapabilities.embedded(s.agentCaps),
        image = AgentCapabilities.image(s.agentCaps),
        sessionList = AgentCapabilities.offersSession(s.agentCaps, "list"),
        terminal = capabilities.terminal.contains(true),
        clipboardOk = true,
        sessionDir = s.sessionCwd,
        mcpCount = 0,
        voice = "ready",
        nodePath = Some(s.settingsState.nodePath).filter(_.nonEmpty),
      )
      post(HostMsg.DoctorReport(findings))
    }

  private def doBtw(text: String): UIO[Unit] =
    val body = text.trim
    if body.isEmpty then post(HostMsg.Error("Usage: /btw <aside>"))
    else
      snap.flatMap { s =>
        val sid = s.sid(fallbackSessionId)
        post(HostMsg.Btw(body, done = false)) *>
          rpc(
            AcpMethod.Interject,
            Json.Obj("sessionId" -> Json.Str(sid.value), "text" -> Json.Str(body)),
          )
      }
    end if
  end doBtw

end ChatRuntime

object ChatRuntime:
  val ProductTitle: String = "Grok's Beard"

  val DefaultModes: List[ModeOption] = List(
    ModeOption(ModeId.Normal, "Normal"),
    ModeOption(ModeId.Auto, "Auto"),
    ModeOption(ModeId.Plan, "Plan"),
    ModeOption(ModeId.AlwaysApprove, "Always approve"),
  )

  def make(
      transport: AcpTransport = AcpTransport.fake(),
      cwd: String = ".",
      capabilities: ClientCapabilities = ClientCapabilities.fake,
      fallbackSessionId: SessionId = SessionId("sess_test"),
      activeFile: () => Option[PromptChip] = () => None,
      includeActiveFile: () => Boolean = () => false,
      settings: () => SettingsState = () => SettingsState.defaults,
      beforeInitialize: UIO[Unit] = ZIO.unit,
  ): ZIO[Scope & ChatEnv.Env, Nothing, ChatRuntime] =
    for
      host      <- ZIO.service[HostOut]
      sessions  <- ZIO.service[SessionRepo]
      mentions  <- ZIO.service[Mentions]
      persist   <- ZIO.service[ChangesPersist]
      copies    <- ZIO.service[TranscriptOut]
      review    <- ZIO.service[ReviewOps]
      terminals <- ZIO.service[Terminals]
      mcps      <- ZIO.service[Mcps]
      prefs     <- ZIO.service[UiPrefs]
      scope     <- ZIO.scope
      gate      <- Semaphore.make(1)
      reentrant <- FiberRef.make(false)
      bag       <- Ref.make(ChatState.seed(cwd, seedSettings(settings(), includeActiveFile), DefaultModes))
      rt = ChatRuntime(
        host,
        transport,
        review,
        sessions,
        mentions,
        persist,
        copies,
        cwd,
        capabilities,
        fallbackSessionId,
        activeFile,
        includeActiveFile,
        settings,
        terminals,
        mcps,
        scope,
        gate,
        reentrant,
        beforeInitialize,
        bag,
        prefs,
      )
      _ <- transport.attach(chunk => rt.exclusive(rt.ingestChunk(chunk)))
      _ <- ZIO.addFinalizer(rt.close)
    yield rt

  def seedSettings(base: SettingsState, includeActiveFile: () => Boolean): SettingsState =
    base.copy(includeActiveFileByDefault = includeActiveFile())

  def patchSettings(state: SettingsState, key: String, value: String | Boolean): SettingsState =
    key match
      case "useCtrlEnterToSend" =>
        value match
          case b: Boolean => state.copy(useCtrlEnterToSend = b)
          case _          => state
      case "includeActiveFileByDefault" =>
        value match
          case b: Boolean => state.copy(includeActiveFileByDefault = b)
          case _          => state
      case "changesPresentation" =>
        value match
          case s: String => state.copy(changesPresentation = s)
          case _         => state
      case "cliPath" =>
        value match
          case s: String => state.copy(cliPath = s)
          case _         => state
      case "nodePath" =>
        value match
          case s: String => state.copy(nodePath = s)
          case _         => state
      case _ => state
end ChatRuntime
