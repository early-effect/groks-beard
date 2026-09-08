package groksbeard.core

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
    includeActiveFile: () => Boolean,
    settings: () => SettingsState,
    terminals: Terminals,
    mcps: Mcps,
    scope: Scope,
    gate: Semaphore,
    reentrant: FiberRef[Boolean],
    beforeInitialize: UIO[Unit],
):
  private val framed                       = Framed(SessionState())
  private val store                        = ChangeStore()
  private val empty                        = EmptySessionTracker()
  private var rpcId                        = 0
  private var turnSeq                      = 0
  private var currentTurn                  = TurnId.mint(0)
  private var currentTitle                 = "Untitled"
  private var sessionId: Option[SessionId] = None
  private var modeId                       = ModeId.Normal
  private var modes: List[ModeOption]      = ChatRuntime.DefaultModes
  private var modelId                      = ModelId.empty
  private var models: List[ModelOption]    = Nil
  private var effort                       = ""
  private val title                        = "Grok's Beard"
  private var running                      = false
  private var chips                        = List.empty[PromptChip]
  private var pendingQueue                 = Vector.empty[QueuedPrompt]
  private var queueSeq                     = 0
  private var settingsState                = ChatRuntime.seedSettings(settings(), includeActiveFile)
  private var occupancy                    = Option.empty[Occupancy]
  private var pendingPerm                  = Map.empty[RequestId, Json]
  private var inbound                      = Map.empty[RequestId, RpcId]
  private var pendingMethod                = Map.empty[RpcId, String]
  private var loading                      = false
  private var loadCleared                  = false
  private var loadModel                    = ChatModel.empty
  private var pendingResume                = Option.empty[SessionId]
  private var pendingLoad                  = Map.empty[RpcId, SessionId]
  private var pendingRewind                = Option.empty[Int]
  private var cancelledLoads               = Set.empty[SessionId]
  private val inboundEpoch                 = new java.util.concurrent.atomic.AtomicInteger(0)
  private var listOpen                     = false
  private var listed                       = List.empty[SessionRow]
  private var live                         = false
  private var initializeSent               = false
  private var initialized                  = false
  private var agentGone                    = false
  private var lastFollow                   = Option.empty[FollowTarget]
  private var liveExecute                  = Option.empty[ToolCallId]
  private var tasks                        = List.empty[TaskRow]
  private var loopSeq                      = 0
  private var loopFibers                   = Map.empty[String, Fiber.Runtime[Nothing, Unit]]
  private var canWorktree                  = false
  private var sessionCwd                   = cwd
  private var pendingForkPrompt            = Option.empty[String]

  private def exclusive[A](body: UIO[A]): UIO[A] =
    reentrant.get.flatMap { held =>
      if held then body
      else gate.withPermit(reentrant.locally(true)(body))
    }

  private def post(msg: HostMsg): UIO[Unit] = host.post(msg)

  private def absorb(io: IO[BeardError, Unit]): UIO[Unit] =
    io.catchAll(e => post(HostMsg.Error(e.message)))

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
      ZIO.suspendSucceed {
        live = true
        val startInit = !initializeSent
        if startInit then initializeSent = true
        post(HostMsg.Ready) *>
          (if store.list.nonEmpty then emitChanges else ZIO.unit).as(startInit)
      }
    }.flatMap { startInit =>
      if !startInit then ZIO.unit
      else
        // Local MCP probes (Metals, etc.) must not gate ACP initialize or the chrome.
        beforeInitialize.forkIn(scope) *> exclusive {
          rpc(
            "initialize",
            InitializeParams(1, capabilities, ClientInfo("groks-beard", title, "0.2.0")).asJson,
          )
        }
    }

  def restoreChanges(sets: List[ChangeSet]): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      store.replace(sets)
      if live && sets.nonEmpty then emitChanges else ZIO.unit
    }
  }

  def send(text: String): UIO[Unit] = exclusive(doSend(text))

  def queue(text: String): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      val trimmed = text.trim
      val chosen  = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
      if trimmed.isEmpty && chosen.isEmpty then ZIO.unit
      else enqueue(trimmed, chosen)
    }
  }

  def sendNow(id: QueueId): UIO[Unit] = exclusive(doSendNow(id))

  def dropQueued(id: QueueId): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      pendingQueue = pendingQueue.filterNot(_.id == id)
      postQueue
    }
  }

  def cancel: UIO[Unit] = exclusive(doCancel)

  def addChip(chip: PromptChip): UIO[Unit] = exclusive(doAddChip(chip))

  def removeChip(absPath: String, startLine: Option[Int], endLine: Option[Int]): UIO[Unit] = exclusive {
    ZIO.succeed {
      chips = chips.filterNot { c =>
        c.absPath == absPath && c.startLine == startLine && c.endLine == endLine
      }
    }
  }

  def currentSettings: SettingsState = settingsState

  def replaceSettings(next: SettingsState): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      settingsState = next
      post(HostMsg.settings(settingsState))
    }
  }

  def setSetting(key: String, value: String | Boolean): UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      settingsState = ChatRuntime.patchSettings(settingsState, key, value)
      post(HostMsg.settings(settingsState))
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

  def mentionPick(path: String, absPath: String): UIO[Unit] =
    exclusive(doAddChip(PromptChip(path, absPath, source = ChipSource.Mention)))

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

  def cycleMode: UIO[Unit] = exclusive(doSetMode(ModeLabel.nextMode(modeId, modes)))

  def setModel(id: ModelId, requested: Option[String] = None): UIO[Unit] = exclusive {
    if id.isEmpty then ZIO.unit else doSetModel(id, requested)
  }

  def setEffort(level: String): UIO[Unit] = exclusive(doSetEffort(level))

  def openDiff(requestId: RequestId): UIO[Unit] = exclusive {
    pendingPerm.get(requestId) match
      case Some(params) =>
        reconstruct(DiffContent.toolCallFromPermission(params), diskIsBefore = true).flatMap { diffs =>
          showDiffs(ChangeSet.turnTitle(currentTitle), diffs)
        }
      case None =>
        store.pending.find(f => f.toolCallId.value == requestId.value || f.path == requestId.value) match
          case Some(file) => showFile(file)
          case None       => openChangesUnlocked
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

  def focusedId: Option[SessionId] = sessionId

  def focusedTitle: String =
    sessionId
      .flatMap(id => listed.find(_.id == id))
      .map(SessionIndex.displayTitle)
      .filter(t => t.nonEmpty && t != title)
      .getOrElse("")

  def renameSession(id: SessionId, op: RenameOp): UIO[Unit] = exclusive(doRename(id, op))

  def deleteSession(id: SessionId): UIO[Unit] = exclusive(doDelete(id))

  def newSession: UIO[Unit] = exclusive(doNewSession)

  def resumeSession(id: SessionId): UIO[Unit] = exclusive(doResume(id))

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

  private def doSend(text: String): UIO[Unit] =
    ZIO.suspendSucceed {
      val trimmed = text.trim
      SessionCommands.intercept(trimmed) match
        case Some(cmd) if SessionCommands.isNew(cmd.name) =>
          doNewSession
        case Some(cmd) if SessionCommands.isResume(cmd.name) || SessionCommands.isHome(cmd.name) =>
          doPostList(open = true)
        case Some(cmd) if SessionCommands.isModel(cmd.name) =>
          if cmd.args.isEmpty then ZIO.unit
          else
            Effort.splitModelArgs(cmd.args, models) match
              case Right((m, e)) => doSetModel(m.modelId, e)
              case Left(err)     => post(HostMsg.Error(err))
        case Some(cmd) if SessionCommands.isEffort(cmd.name) =>
          if cmd.args.isEmpty then ZIO.unit else doSetEffort(cmd.args)
        case Some(cmd) if SessionCommands.isRename(cmd.name) =>
          SessionEdit.parseRename(cmd.args) match
            case Left("empty") => ZIO.unit
            case Left(err)     => post(HostMsg.Error(err))
            case Right(op)     => doRename(sessionId.getOrElse(SessionId.empty), op)
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
        case _ =>
          val chosen = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
          if trimmed.isEmpty && chosen.isEmpty then ZIO.unit
          else if running || !initialized then enqueue(trimmed, chosen)
          else
            chips = Nil
            runTurn(trimmed, chosen)
      end match
    }

  private def doListMcps: UIO[Unit] =
    absorb(mcps.list.flatMap(rows => post(HostMsg.McpServers(rows))))

  private def doOpenRewind: UIO[Unit] =
    if running then post(HostMsg.Error("Stop the turn before rewinding."))
    else
      val sid = sessionId.getOrElse(fallbackSessionId)
      rpc("x.ai/rewind/points", RewindPointsParams(sid).asJson)

  private def doRewindTo(promptIndex: Int): UIO[Unit] =
    if running then post(HostMsg.Error("Stop the turn before rewinding."))
    else
      val sid = sessionId.getOrElse(fallbackSessionId)
      pendingRewind = Some(promptIndex)
      rpc("x.ai/rewind/execute", RewindExecuteParams(sid, promptIndex).asJson)

  private def doAgentGone: UIO[Unit] =
    ZIO.suspendSucceed {
      if agentGone then ZIO.unit
      else
        agentGone = true
        val load = pendingResume match
          case None     => ZIO.unit
          case Some(id) =>
            cancelledLoads += id
            loading = false
            pendingResume = None
            pendingLoad = Map.empty
            post(HostMsg.SessionLocked(id, SessionLoad.copy(SessionLoadKind.Failed)))
        val turn =
          if !running then ZIO.unit
          else
            running = false
            post(HostMsg.TurnEnd(currentTurn, StopReason.Cancelled))
        post(HostMsg.Error("Grok agent stopped.")) *> load *> turn
    }

  private def doCancel: UIO[Unit] = stopTurn *> drainQueue

  private def doSendNow(id: QueueId): UIO[Unit] =
    ZIO.suspendSucceed {
      pendingQueue.find(_.id == id) match
        case None       => ZIO.unit
        case Some(item) =>
          pendingQueue = item +: pendingQueue.filterNot(_.id == id)
          stopTurn *> drainQueue
    }

  private def stopTurn: UIO[Unit] =
    ZIO.foreachDiscard(pendingPerm.keys.toList) { id =>
      respond(id, Json.Obj("outcome" -> Json.Obj("outcome" -> Json.Str("cancelled"))))
    } *> ZIO.suspendSucceed {
      val sid = sessionId.getOrElse(fallbackSessionId)
      notify("session/cancel", SessionCancelParams(sid).asJson) *>
        (if running then
           running = false
           post(HostMsg.TurnEnd(currentTurn, StopReason.Cancelled))
         else ZIO.unit)
    }

  private def doAddChip(chip: PromptChip): UIO[Unit] =
    ZIO.suspendSucceed {
      chips = PromptChip.upsert(chips, chip)
      post(HostMsg.chip(chip))
    }

  private def doSetMode(id: ModeId): UIO[Unit] =
    ZIO.suspendSucceed {
      val sid = sessionId.getOrElse(fallbackSessionId)
      rpc("session/set_mode", SessionSetModeParams(sid, id).asJson) *>
        ZIO.succeed {
          modeId = id
          framed.state.commitMode(id)
        } *> postMeta
    }

  private def currentModel: Option[ModelOption] =
    models.find(_.modelId == modelId)

  private def doSetModel(id: ModelId, requested: Option[String]): UIO[Unit] =
    val target  = models.find(_.modelId == id)
    val allowed = Effort.of(target)
    requested match
      case Some(raw) =>
        Effort.pick(raw, allowed) match
          case None        => post(HostMsg.Error(Effort.unknown(raw, allowed)))
          case Some(level) => writeModel(id, Some(level.value), level.value)
      case None =>
        val carried = Option(effort).filter(_.nonEmpty).filter(e => allowed.exists(_.value == e))
        writeModel(id, carried, carried.getOrElse(Effort.defaultOf(target)))
  end doSetModel

  private def doSetEffort(raw: String): UIO[Unit] =
    val allowed = Effort.of(currentModel)
    if modelId.isEmpty then post(HostMsg.Error(Effort.NoModel))
    else
      Effort.pick(raw, allowed) match
        case None        => post(HostMsg.Error(Effort.unknown(raw, allowed)))
        case Some(level) => writeModel(modelId, Some(level.value), level.value)

  private def writeModel(id: ModelId, sendEffort: Option[String], display: String): UIO[Unit] =
    rpc("session/set_model", setModelJson(id, sendEffort)) *>
      ZIO.succeed {
        modelId = id
        effort = display
      } *> postMeta

  private def setModelJson(id: ModelId, sendEffort: Option[String]): Json =
    val sid = sessionId.getOrElse(fallbackSessionId)
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
    end match
  end setModelJson

  private def doRename(id: SessionId, op: RenameOp): UIO[Unit] =
    val target = if id.nonEmpty then id else sessionId.getOrElse(SessionId.empty)
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
                case Some(_) => postMeta *> doPostList(listOpen)
              },
            )
    end if
  end doRename

  private def doDelete(id: SessionId): UIO[Unit] =
    val target = if id.nonEmpty then id else sessionId.getOrElse(SessionId.empty)
    if target.isEmpty then post(HostMsg.Error("No session to delete"))
    else
      sessions
        .delete(target)
        .foldZIO(
          e => post(HostMsg.Error(e.message)),
          gone =>
            if !gone then post(HostMsg.Error("Could not delete session"))
            else if sessionId.contains(target) then doNewSession
            else doPostList(listOpen),
        )
    end if
  end doDelete

  private def doNewSession: UIO[Unit] =
    inboundEpoch.incrementAndGet()
    pendingResume.foreach(id => cancelledLoads += id)
    leaveCurrent *> stopLoops *> ZIO.suspendSucceed {
      resetLocal()
      sessionId = None
      post(HostMsg.ClearTranscript) *> rpc("session/new", SessionNewParams(sessionCwd).asJson)
    }

  private def doResume(id: SessionId): UIO[Unit] =
    if id.isEmpty then doPostList(open = true)
    else if pendingResume.contains(id) && !initialized then ZIO.unit
    else if sessionId.contains(id) && pendingResume.isEmpty && !loading then doPostList(open = false)
    else
      inboundEpoch.incrementAndGet()
      pendingResume.foreach(prev => cancelledLoads += prev)
      leaveCurrent *> stopLoops *> ZIO.suspendSucceed {
        cancelledLoads -= id
        resetTurnState()
        sessionId = Some(id)
        loading = true
        loadCleared = true
        pendingResume = Some(id)
        val load =
          if initialized then rpc("session/load", SessionLoadParams(id, sessionCwd).asJson, loadSessionId = Some(id))
          else ZIO.unit
        post(HostMsg.ClearTranscript) *> postMeta *> doPostList(open = false) *> load
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

  private def enqueue(text: String, chosen: List[PromptChip]): UIO[Unit] =
    ZIO.suspendSucceed {
      chips = Nil
      queueSeq += 1
      pendingQueue = pendingQueue :+ QueuedPrompt(QueueId.mint(queueSeq), text, chosen)
      postQueue
    }

  private def drainQueue: UIO[Unit] =
    ZIO.suspendSucceed {
      if pendingQueue.isEmpty then postQueue
      else
        val item = pendingQueue.head
        pendingQueue = pendingQueue.tail
        postQueue *> runTurn(item.text, item.chips)
    }

  private def postQueue: UIO[Unit] =
    post(HostMsg.Queued(pendingQueue.toList))

  private def runTurn(text: String, chosen: List[PromptChip]): UIO[Unit] =
    ZIO.suspendSucceed {
      running = true
      liveExecute = None
      turnSeq += 1
      currentTurn = TurnId.mint(turnSeq)
      currentTitle =
        ChangeSet.turnTitle(if text.nonEmpty then text else chosen.headOption.map(_.path).getOrElse("chip"))
      sessionId.foreach(empty.markHasHistory)
      val body = PromptChip.buildPromptText(text, chosen)
      val sid  = sessionId.getOrElse(fallbackSessionId)
      post(HostMsg.UserMessage(currentTurn, text, chosen)) *>
        rpc("session/prompt", SessionPromptParams(sid, List(PromptText(text = body))).asJson)
    }

  private def leaveCurrent: UIO[Unit] =
    sessionId match
      case None     => ZIO.unit
      case Some(id) =>
        val del = if empty.shouldDelete(id) then sessions.scheduleEmptyDelete(id) else ZIO.unit
        empty.forget(id)
        del

  private def resetTurnState(): Unit =
    turnSeq = 0
    currentTurn = TurnId.mint(0)
    currentTitle = "Untitled"
    chips = Nil
    pendingQueue = Vector.empty
    queueSeq = 0
    occupancy = None
    running = false
    liveExecute = None
    loadModel = ChatModel.empty
    lastFollow = None
    tasks = Nil
  end resetTurnState

  private def resetLocal(): Unit =
    resetTurnState()
    loading = false
    loadCleared = false
    pendingResume = None

  private def doPostList(open: Boolean): UIO[Unit] =
    listOpen = open
    val sid  = sessionId.getOrElse(SessionId.empty)
    val skip = empty.shouldDelete(sid)
    sessions.list
      .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
      .zip(Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
      .flatMap { (rows, now) =>
        listed = SessionIndex.touchCurrent(rows, sid, now, skip)
        post(HostMsg.SessionList(listed, sid, openPicker = open))
      }
  end doPostList

  private def respond(requestId: RequestId, result: Json): UIO[Unit] =
    inbound.get(requestId) match
      case None     => ZIO.unit
      case Some(id) =>
        inbound -= requestId
        pendingPerm -= requestId
        absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.ok(id, result)))))

  private def reject(requestId: RequestId, message: String): UIO[Unit] =
    inbound.get(requestId) match
      case None     => ZIO.unit
      case Some(id) =>
        inbound -= requestId
        absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.fail(id, Rpc.InvalidParams, message)))))

  private def terminalIdOf(params: Json): Option[TerminalId] =
    jsonStr(params, "terminalId").flatMap(TerminalId.fromWire)

  private def handleTerminalCreate(requestId: RequestId, params: Json): UIO[Unit] =
    val parsed = params.as[TerminalCreateParams]
    parsed match
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
    end match
  end handleTerminalCreate

  private def bindTerminal(requestId: RequestId, id: TerminalId, p: TerminalCreateParams): UIO[Unit] =
    val toolId = liveExecute.getOrElse(ToolCallId(id.value))
    val start  =
      if liveExecute.isDefined then ZIO.unit
      else
        val cmd =
          if p.args.isEmpty then p.command
          else s"${p.command} ${p.args.mkString(" ")}"
        liveExecute = Some(toolId)
        post(
          HostMsg.ToolCall(
            currentTurn,
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
  end bindTerminal

  private def watchTerminal(id: TerminalId, toolId: ToolCallId): UIO[Unit] =
    val turn = currentTurn
    terminals.stream(id).flatMap {
      case None         => ZIO.unit
      case Some(chunks) =>
        chunks
          .filter(_.nonEmpty)
          .foreach(text => exclusive(post(HostMsg.ToolChunk(turn, toolId, text))))
          .forkIn(scope)
          .unit
    }
  end watchTerminal

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
    val current = if loading then loadModel.tasks else tasks
    Tasks.fold(p, current) match
      case None       => ZIO.succeed(false)
      case Some(next) =>
        val notices = Tasks.notices(current, next)
        if loading then loadModel = loadModel.copy(tasks = next)
        else tasks = next
        val snap = post(HostMsg.Tasks(next))
        val note = if loading then ZIO.unit else ZIO.foreachDiscard(notices)(post)
        (snap *> note).as(true)
  end foldTasks

  private def doLoop(args: String): UIO[Unit] =
    Tasks.parseLoop(args) match
      case Left(err)   => post(HostMsg.Error(err))
      case Right(spec) =>
        loopSeq += 1
        val id  = TaskId.mint(loopSeq)
        val row =
          TaskRow(
            id = id,
            kind = TaskKind.Loop,
            status = TaskStatus.Running,
            label = spec.prompt,
            detail = spec.human,
            owned = true,
          )
        tasks = Tasks.upsert(tasks, row)
        post(HostMsg.Tasks(tasks)) *> startLoop(id, spec)

  private def doFork(args: ForkArgs): UIO[Unit] =
    sessionId.filter(_.nonEmpty) match
      case None    => post(HostMsg.Error(Fork.NoSession))
      case Some(_) =>
        args.worktree match
          case None if canWorktree =>
            post(HostMsg.ForkAsk(args.directive.getOrElse("")))
          case None =>
            runFork(worktree = false, args.directive)
          case Some(true) if !canWorktree =>
            post(HostMsg.Error(Fork.MissingWorktree))
          case Some(w) =>
            runFork(w, args.directive)

  private def runFork(worktree: Boolean, directive: Option[String]): UIO[Unit] =
    sessionId.filter(_.nonEmpty) match
      case None      => post(HostMsg.Error(Fork.NoSession))
      case Some(sid) =>
        pendingForkPrompt = directive
        rpc(
          "_x.ai/session/fork",
          ForkSessionParams(
            sourceSessionId = sid,
            sourceCwd = sessionCwd,
            newCwd = sessionCwd,
            sessionKind = Some(if worktree then "worktree" else "fork"),
            sourceWorkspaceDir = if worktree then Some(cwd) else None,
          ).asJson,
        )

  private def ingestFork(result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    error match
      case Some(err) if err.code == Rpc.MethodNotFound =>
        pendingForkPrompt = None
        post(HostMsg.Error(Fork.MissingCli))
      case Some(err) =>
        pendingForkPrompt = None
        post(HostMsg.Error(err.message))
      case None =>
        result.flatMap(_.as[ForkSessionResult].toOption) match
          case None =>
            pendingForkPrompt = None
            post(HostMsg.Error("Fork failed"))
          case Some(forked) =>
            if forked.newCwd.nonEmpty then sessionCwd = forked.newCwd
            doResume(forked.newSessionId)

  private def sendForkPrompt: UIO[Unit] =
    pendingForkPrompt match
      case None       => ZIO.unit
      case Some(text) =>
        pendingForkPrompt = None
        if text.isEmpty then ZIO.unit
        else runTurn(text, Nil)

  private def startLoop(id: TaskId, spec: LoopSpec): UIO[Unit] =
    def fire: UIO[Unit] =
      exclusive(
        ZIO.suspendSucceed {
          if running then enqueue(spec.prompt, Nil)
          else runTurn(spec.prompt, Nil)
        }
      )
    for
      _     <- fire
      fiber <- (ZIO.sleep(spec.interval) *> fire).forever.unit.forkIn(scope)
    yield loopFibers = loopFibers.updated(id.value, fiber)
  end startLoop

  private def doStopTask(id: TaskId): UIO[Unit] =
    val fiber = loopFibers.get(id.value)
    loopFibers = loopFibers - id.value
    val row  = tasks.find(_.id == id)
    val next =
      if row.exists(_.owned) then Tasks.remove(tasks, id)
      else tasks
    tasks = next
    val interrupt = fiber.map(_.interrupt.unit).getOrElse(ZIO.unit)
    interrupt *> post(HostMsg.Tasks(tasks))
  end doStopTask

  private def stopLoops: UIO[Unit] =
    val fs = loopFibers
    loopFibers = Map.empty
    ZIO.foreachDiscard(fs.values)(_.interrupt.unit)

  private def grokMethod(method: String): String =
    if method.startsWith("x.ai/") && !method.startsWith("_x.ai/") then s"_$method" else method

  private def notify(method: String, params: Json): UIO[Unit] =
    absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.notify(method, params)))))

  private def rpc(method: String, params: Json, loadSessionId: Option[SessionId] = None): UIO[Unit] =
    ZIO.suspendSucceed {
      rpcId += 1
      val id  = RpcId.Num(rpcId)
      val req = Rpc.Request(id, method, params)
      pendingMethod = pendingMethod.updated(id, method)
      loadSessionId.foreach(sid => pendingLoad = pendingLoad.updated(id, sid))
      framed.recordOutgoing(req)
      absorb(transport.write(Ndjson.encode(Rpc.toLine(req))))
    }

  private def ingestChunk(chunk: String): UIO[Unit] =
    val epoch = inboundEpoch.get()
    val msgs  = framed.feed(chunk)
    ZIO.foreachDiscard(msgs) { msg =>
      if inboundEpoch.get() == epoch then ingestOne(msg) else ZIO.unit
    }

  private def ingestOne(msg: Rpc): UIO[Unit] =
    msg match
      case Rpc.Notify(method, p) if SessionUpdate.isSessionNotify(method) =>
        val updateSid = SessionState.decodeNotify(p).map(_.sessionId).filter(_.nonEmpty)
        val stale     =
          updateSid.exists(cancelledLoads.contains) ||
            updateSid.exists(sid => sessionId.exists(_ != sid))
        if stale then ZIO.unit
        else if loading then ingestLoading(p) *> ingestUpdate(p)
        else ingestLive(p) *> ingestUpdate(p)
      case Rpc.Request(rid, rawMethod, params) =>
        val reqId  = requestKey(rid)
        val method = grokMethod(rawMethod)
        inbound = inbound.updated(reqId, rid)
        method match
          case "session/request_permission" =>
            pendingPerm = pendingPerm.updated(reqId, params)
            post(HostMsg.permission(DiffContent.permissionCard(params, reqId)))
          case "_x.ai/exit_plan_mode" =>
            val md =
              jsonStr(params, "planContent").orElse(jsonStr(params, "planMarkdown")).getOrElse("")
            post(HostMsg.plan(PlanCard(reqId, md)))
          case "_x.ai/ask_user_question" =>
            val questions =
              params.as[AskUserQuestionParams].toOption.map(_.questions).getOrElse(Nil)
            post(HostMsg.question(QuestionCard(reqId, questions)))
          case "elicitation/create" | "_x.ai/mcp/elicit" =>
            post(HostMsg.elicit(elicitCard(params, reqId)))
          case "terminal/create" | "_x.ai/terminal/create" =>
            handleTerminalCreate(reqId, params)
          case "terminal/output" | "_x.ai/terminal/output" =>
            handleTerminalOutput(reqId, params)
          case "terminal/wait_for_exit" | "_x.ai/terminal/wait_for_exit" =>
            handleTerminalWait(reqId, params)
          case "terminal/kill" | "_x.ai/terminal/kill" =>
            handleTerminalKill(reqId, params)
          case "terminal/release" | "_x.ai/terminal/release" =>
            handleTerminalRelease(reqId, params)
          case _ =>
            inbound -= reqId
            absorb(
              transport.write(
                Ndjson.encode(Rpc.toLine(Rpc.fail(rid, Rpc.MethodNotFound, s"Method not found: $rawMethod")))
              )
            )
        end match
      case Rpc.Response(id, result, error) =>
        ingestResponse(id, result, error)
      case _ => ZIO.unit

  private def ingestLoading(p: Json): UIO[Unit] =
    ZIO.suspendSucceed {
      SessionState.decodeUpdate(p) match
        case Some(_: AcpUpdate.User) =>
          if !loadCleared then loadCleared = true
          turnSeq += 1
          currentTurn = TurnId.mint(turnSeq)
        case _ =>
          if !loadCleared then loadCleared = true
      foldTasks(p, loading = true) *>
        ZIO.foreachDiscard(SessionUpdate.hostMsgs(p, currentTurn)) {
          case HostMsg.AvailableCommands(cmds) =>
            post(HostMsg.AvailableCommands(SessionCommands.merge(cmds)))
          case other =>
            ZIO.succeed {
              other match
                case m: HostMsg.SessionMeta => m.occupancy.foreach(o => occupancy = Some(o))
                case _                      => ()
              loadModel = ChatModel.applyMsg(loadModel, other)
            }
        }
    }

  private def ingestLive(p: Json): UIO[Unit] =
    foldTasks(p, loading = false).flatMap { folded =>
      val msgs = SessionUpdate.hostMsgs(p, currentTurn)
      val note =
        if msgs.nonEmpty || folded then ZIO.unit
        else
          SessionState.decodeUpdate(p) match
            case Some(_) => ZIO.unit
            case None    => ZIO.logWarning(s"ignored session/update ${sessionUpdateKind(p)}")
      note *> ZIO.foreachDiscard(msgs) { msg =>
        val out = msg match
          case HostMsg.AvailableCommands(cmds) => HostMsg.AvailableCommands(SessionCommands.merge(cmds))
          case other                           => other
        out match
          case m: HostMsg.SessionMeta   => m.occupancy.foreach(o => occupancy = Some(o))
          case HostMsg.ToolCall(_, row) => noteLiveTool(row)
          case _                        => ()
        post(out)
      }
    }
  end ingestLive

  private def noteLiveTool(row: ToolRow): Unit =
    if ToolStatus.isLive(row.status) && isExecuteTool(row) then liveExecute = Some(row.id)
    else if liveExecute.contains(row.id) && !ToolStatus.isLive(row.status) then liveExecute = None

  private def isExecuteTool(row: ToolRow): Boolean =
    row.kind == ToolKind.Execute ||
      row.title.contains("terminal") ||
      row.title.toLowerCase.contains("execute")

  private def ingestResponse(id: RpcId, result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    ZIO.suspendSucceed {
      val method = pendingMethod.getOrElse(id, "")
      pendingMethod -= id
      val errPost =
        if method == "session/load" || method.endsWith("rewind/points") || method.endsWith("session/fork") then ZIO.unit
        else error.map(e => post(HostMsg.Error(e.message))).getOrElse(ZIO.unit)
      errPost *> (method match
        case "initialize" =>
          initialized = true
          canWorktree = result.exists(Fork.offersWorktree)
          pendingResume match
            case Some(id) => rpc("session/load", SessionLoadParams(id, sessionCwd).asJson, loadSessionId = Some(id))
            case None     => rpc("session/new", SessionNewParams(sessionCwd).asJson)
        case "session/new" =>
          result match
            case None       => ZIO.unit
            case Some(json) =>
              json.as[SessionNewResult] match
                case Left(_)        => ZIO.unit
                case Right(decoded) =>
                  ZIO.suspendSucceed {
                    empty.markCreated(decoded.sessionId)
                    val steal =
                      pendingResume.nonEmpty || sessionId.exists(cur => cur.nonEmpty && cur != decoded.sessionId)
                    if steal then
                      if empty.shouldDelete(decoded.sessionId) then sessions.scheduleEmptyDelete(decoded.sessionId)
                      else ZIO.unit
                    else
                      applySession(json)
                      if sessionId.isEmpty then sessionId = Some(fallbackSessionId)
                      postMeta *> post(HostMsg.settings(settingsState)) *> doPostList(open = false) *>
                        (if pendingQueue.nonEmpty then drainQueue else ZIO.unit)
                  }
        case "session/load" =>
          ingestLoad(id, result, error)
        case m if m.endsWith("rewind/points") =>
          if error.isDefined then ZIO.unit
          else post(HostMsg.RewindList(Rewind.decodePoints(result.getOrElse(Json.Null))))
        case m if m.endsWith("session/fork") =>
          ingestFork(result, error)
        case m if m.endsWith("rewind/execute") =>
          val idx = pendingRewind
          pendingRewind = None
          if error.isDefined then ZIO.unit
          else
            idx match
              case None    => ZIO.unit
              case Some(i) =>
                pendingQueue = Vector.empty
                store.keepAll()
                postQueue *> postChanges *> post(HostMsg.ClearDiff) *> post(HostMsg.Rewound(i))
        case "session/prompt" =>
          val reason =
            result.flatMap(_.as[SessionPromptResult].toOption).map(_.stopReason).getOrElse(StopReason.EndTurn)
          post(HostMsg.TurnEnd(currentTurn, reason)) *>
            ZIO.succeed { running = false } *> drainQueue
        case _ => ZIO.unit)
    }

  private def ingestLoad(id: RpcId, result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    ZIO.suspendSucceed {
      val loadSid = pendingLoad.get(id)
      pendingLoad -= id
      val stale =
        loadSid.exists(cancelledLoads.contains) ||
          pendingResume.exists(want => loadSid.exists(_ != want)) ||
          pendingResume.isEmpty
      if stale then ZIO.unit
      else
        loading = false
        val wanted = pendingResume
        pendingResume = None
        error match
          case Some(err) =>
            val kind = SessionLoad.classify(err.message)
            val sid  = wanted.getOrElse(SessionId.empty)
            ZIO.succeed { pendingForkPrompt = None } *>
              post(HostMsg.SessionLocked(sid, SessionLoad.copy(kind))) *>
              (if kind == SessionLoadKind.Failed then post(HostMsg.Error(err.message)) else ZIO.unit)
          case None =>
            val clear       = if !loadCleared then post(HostMsg.ClearTranscript) else ZIO.unit
            val snap        = ChatModel.snapshotTurns(loadModel.turns)
            val todos       = loadModel.todos
            val loadedTasks = loadModel.tasks
            val sid         = wanted.getOrElse(SessionId.empty)
            loadModel = ChatModel.empty
            tasks = loadedTasks
            clear *>
              post(HostMsg.Transcript(snap)) *>
              postLoadedTodos(sid, todos) *>
              (if loadedTasks.isEmpty then ZIO.unit else post(HostMsg.Tasks(loadedTasks))) *>
              ZIO.succeed(result.foreach(applySession)) *>
              ZIO.succeed {
                wanted.foreach { loadId =>
                  if sessionId.isEmpty then sessionId = Some(loadId)
                  empty.markHasHistory(sessionId.getOrElse(loadId))
                }
              } *> postMeta *> post(HostMsg.settings(settingsState)) *> doPostList(open = false) *>
              (if pendingQueue.nonEmpty then drainQueue else ZIO.unit) *> sendForkPrompt
        end match
      end if
    }

  private def applySession(json: Json): Unit =
    json.as[SessionNewResult].foreach { decoded =>
      sessionId = Some(decoded.sessionId)
      decoded.modes.foreach { state =>
        modeId = state.currentModeId
        framed.state.commitMode(state.currentModeId)
        if state.availableModes.nonEmpty then modes = state.availableModes
      }
      decoded.models.foreach { state =>
        modelId = state.currentModelId
        if state.availableModels.nonEmpty then models = state.availableModels
        effort = Effort.activeOf(models.find(_.modelId == modelId))
      }
    }

  private def ingestUpdate(params: Json): UIO[Unit] =
    SessionState.decodeUpdate(params) match
      case Some(call: AcpUpdate.ToolCall)       => ingestTool(call.status, toBody(call))
      case Some(call: AcpUpdate.ToolCallUpdate) => ingestTool(call.status, toBody(call))
      case _                                    => ZIO.unit

  private def ingestTool(status: ToolStatus, body: AcpToolCall): UIO[Unit] =
    followLocations(body) *>
      reconstruct(body.asJson, DiffContent.diskIsBefore(status)).flatMap { diffs =>
        if diffs.isEmpty then ZIO.unit
        else
          ZIO.suspendSucceed {
            store.ingest(
              sessionId.getOrElse(fallbackSessionId),
              currentTurn,
              currentTitle,
              diffs.map(DiffContent.fileChangeFrom),
            )
            postChanges
          }
      }
  end ingestTool

  private def followLocations(body: AcpToolCall): UIO[Unit] =
    FollowAlong.pick(body.locations, body.toolCallId) match
      case Some(next) if FollowAlong.changed(lastFollow, next) =>
        lastFollow = Some(next)
        review.follow(next.path, next.line)
      case _ => ZIO.unit

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
    val sid = sessionId.getOrElse(SessionId.empty)
    sessions.list
      .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
      .flatMap { rows =>
        val named =
          rows
            .find(_.id == sid)
            .map(SessionIndex.displayTitle)
            .filter(_.nonEmpty)
            .getOrElse(title)
        post(HostMsg.SessionMeta(sid, named, modeId, modes, occupancy, modelId, models, effort, cwd))
      }
  end postMeta

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
end ChatRuntime

object ChatRuntime:
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
      scope     <- ZIO.scope
      gate      <- Semaphore.make(1)
      reentrant <- FiberRef.make(false)
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
