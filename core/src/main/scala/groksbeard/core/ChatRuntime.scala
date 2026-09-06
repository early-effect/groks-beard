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
    fallbackSessionId: String,
    activeFile: () => Option[PromptChip],
    includeActiveFile: () => Boolean,
    settings: () => SettingsState,
    gate: Semaphore,
    reentrant: FiberRef[Boolean],
):
  private val framed                    = Framed(SessionState())
  private val store                     = ChangeStore()
  private val empty                     = EmptySessionTracker()
  private var rpcId                     = 0
  private var turnSeq                   = 0
  private var currentTurn               = "turn_0"
  private var currentTitle              = "Untitled"
  private var sessionId: Option[String] = None
  private var modeId                    = "normal"
  private var modes: List[ModeOption]   = ChatRuntime.DefaultModes
  private var modelId                   = ""
  private var models: List[ModelOption] = Nil
  private val title                     = "Grok's Beard"
  private var running                   = false
  private var chips                     = List.empty[PromptChip]
  private var pendingQueue              = Vector.empty[QueuedPrompt]
  private var queueSeq                  = 0
  private var settingsState             = ChatRuntime.seedSettings(settings(), includeActiveFile)
  private var occupancy                 = Option.empty[Occupancy]
  private var pendingPerm               = Map.empty[String, Json]
  private var inbound                   = Map.empty[String, RpcId]
  private var pendingMethod             = Map.empty[RpcId, String]
  private var loading                   = false
  private var loadCleared               = false
  private var loadModel                 = ChatModel.empty
  private var pendingResume             = Option.empty[String]
  private var pendingLoad               = Map.empty[RpcId, String]
  private var cancelledLoads            = Set.empty[String]
  private val inboundEpoch              = new java.util.concurrent.atomic.AtomicInteger(0)
  private var listOpen                  = false
  private var listed                    = List.empty[SessionRow]
  private var live                      = false

  private def exclusive(body: UIO[Unit]): UIO[Unit] =
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
      case Some(msg) if running => post(HostMsg.Error(msg)) *> doCancel
      case _                    => ZIO.unit
  }

  def ready: UIO[Unit] = exclusive {
    ZIO.suspendSucceed {
      live = true
      post(HostMsg.Ready) *>
        rpc(
          "initialize",
          InitializeParams(1, capabilities, ClientInfo("groks-beard", title, "0.2.0")).asJson,
        ) *> (if store.list.nonEmpty then emitChanges else ZIO.unit)
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
    exclusive(doAddChip(PromptChip(path, absPath, source = "mention")))

  def permissionChoice(requestId: String, optionId: String): UIO[Unit] = exclusive {
    respond(
      requestId,
      Json.Obj(
        "outcome" -> Json.Obj("outcome" -> Json.Str("selected"), "optionId" -> Json.Str(optionId))
      ),
    )
  }

  def planVerdict(requestId: String, verdict: String): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("outcome" -> Json.Str(verdict)))
  }

  def questionSubmit(requestId: String, answers: List[QuestionAnswer]): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("answers" -> answers.asJson))
  }

  def questionDismiss(requestId: String): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("answers" -> Json.Arr()))
  }

  def elicitAccept(requestId: String): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("action" -> Json.Str("accept")))
  }

  def elicitDecline(requestId: String): UIO[Unit] = exclusive {
    respond(requestId, Json.Obj("action" -> Json.Str("decline")))
  }

  def setMode(id: String): UIO[Unit] = exclusive(doSetMode(id))

  def cycleMode: UIO[Unit] = exclusive(doSetMode(ModeLabel.nextMode(modeId, modes)))

  def setModel(id: String): UIO[Unit] = exclusive {
    if id.isEmpty then ZIO.unit
    else
      ZIO.suspendSucceed {
        val sid = sessionId.getOrElse(fallbackSessionId)
        rpc("session/set_model", SessionSetModelParams(sid, id).asJson) *>
          ZIO.succeed { modelId = id } *>
          postMeta
      }
  }

  def openDiff(requestId: String): UIO[Unit] = exclusive {
    pendingPerm.get(requestId) match
      case Some(params) =>
        reconstruct(DiffContent.toolCallFromPermission(params), diskIsBefore = true).flatMap { diffs =>
          showDiffs(ChangeSet.turnTitle(currentTitle), diffs)
        }
      case None =>
        store.pending.find(f => f.toolCallId == requestId || f.path == requestId) match
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

  def keepTurn(turnId: String): UIO[Unit] = exclusive {
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

  def undoTurn(turnId: String): UIO[Unit] = exclusive(undoFiles(store.filesOf(turnId)))

  def undoAll: UIO[Unit] = exclusive(undoFiles(store.pending))

  def closeDiff: UIO[Unit] = exclusive(post(HostMsg.ClearDiff))

  def pendingChanges: List[FileChange] = store.pending

  def pendingSets: List[ChangeSet] = store.list

  def slashPick(name: String): UIO[Unit] = exclusive {
    if SessionCommands.isNew(name) then doNewSession
    else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then doPostList(open = true)
    else ZIO.unit
  }

  def focusedId: Option[String] = sessionId

  def focusedTitle: String =
    sessionId
      .flatMap(id => listed.find(_.id == id))
      .map(SessionIndex.displayTitle)
      .filter(t => t.nonEmpty && t != title)
      .getOrElse("")

  def renameSession(id: String, op: RenameOp): UIO[Unit] = exclusive(doRename(id, op))

  def deleteSession(id: String): UIO[Unit] = exclusive(doDelete(id))

  def newSession: UIO[Unit] = exclusive(doNewSession)

  def resumeSession(id: String): UIO[Unit] = exclusive(doResume(id))

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
            ModelOption.pick(cmd.args, models) match
              case Some(m) =>
                val sid = sessionId.getOrElse(fallbackSessionId)
                rpc("session/set_model", SessionSetModelParams(sid, m.modelId).asJson) *>
                  ZIO.succeed { modelId = m.modelId } *>
                  postMeta
              case None => post(HostMsg.Error(s"Unknown model: ${cmd.args}"))
        case Some(cmd) if SessionCommands.isRename(cmd.name) =>
          SessionEdit.parseRename(cmd.args) match
            case Left("empty") => ZIO.unit
            case Left(err)     => post(HostMsg.Error(err))
            case Right(op)     => doRename(sessionId.getOrElse(""), op)
        case Some(cmd) if SessionCommands.isDelete(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isHistory(cmd.name) =>
          ZIO.unit
        case Some(cmd) if SessionCommands.isCopy(cmd.name) || SessionCommands.isExport(cmd.name) =>
          ZIO.unit
        case _ =>
          val chosen = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
          if trimmed.isEmpty && chosen.isEmpty then ZIO.unit
          else if running then enqueue(trimmed, chosen)
          else
            chips = Nil
            runTurn(trimmed, chosen)
      end match
    }

  private def doCancel: UIO[Unit] =
    ZIO.foreachDiscard(pendingPerm.keys.toList) { id =>
      respond(id, Json.Obj("outcome" -> Json.Obj("outcome" -> Json.Str("cancelled"))))
    } *> ZIO.suspendSucceed {
      val sid = sessionId.getOrElse(fallbackSessionId)
      notify("session/cancel", SessionCancelParams(sid).asJson) *>
        (if running then
           running = false
           post(HostMsg.TurnEnd(currentTurn, "cancelled"))
         else ZIO.unit) *> drainQueue
    }

  private def doAddChip(chip: PromptChip): UIO[Unit] =
    ZIO.suspendSucceed {
      chips = PromptChip.upsert(chips, chip)
      post(HostMsg.chip(chip))
    }

  private def doSetMode(id: String): UIO[Unit] =
    ZIO.suspendSucceed {
      val sid = sessionId.getOrElse(fallbackSessionId)
      rpc("session/set_mode", SessionSetModeParams(sid, id).asJson) *>
        ZIO.succeed {
          modeId = id
          framed.state.commitMode(id)
        } *> postMeta
    }

  private def doRename(id: String, op: RenameOp): UIO[Unit] =
    val target = if id.nonEmpty then id else sessionId.getOrElse("")
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

  private def doDelete(id: String): UIO[Unit] =
    val target = if id.nonEmpty then id else sessionId.getOrElse("")
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
    leaveCurrent *> ZIO.suspendSucceed {
      resetLocal()
      sessionId = None
      post(HostMsg.ClearTranscript) *> rpc("session/new", SessionNewParams(cwd).asJson)
    }

  private def doResume(id: String): UIO[Unit] =
    if id.isEmpty then doPostList(open = true)
    else if sessionId.contains(id) then doPostList(open = false)
    else
      inboundEpoch.incrementAndGet()
      pendingResume.foreach(prev => cancelledLoads += prev)
      leaveCurrent *> ZIO.suspendSucceed {
        cancelledLoads -= id
        resetTurnState()
        sessionId = Some(id)
        loading = true
        loadCleared = true
        pendingResume = Some(id)
        post(HostMsg.ClearTranscript) *>
          postMeta *>
          doPostList(open = false) *>
          rpc("session/load", SessionLoadParams(id, cwd).asJson, loadSessionId = Some(id))
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
      pendingQueue = pendingQueue :+ QueuedPrompt(s"q$queueSeq", text, chosen)
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
      turnSeq += 1
      currentTurn = s"turn_$turnSeq"
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
    currentTurn = "turn_0"
    currentTitle = "Untitled"
    chips = Nil
    pendingQueue = Vector.empty
    queueSeq = 0
    occupancy = None
    running = false
    loadModel = ChatModel.empty
  end resetTurnState

  private def resetLocal(): Unit =
    resetTurnState()
    loading = false
    loadCleared = false
    pendingResume = None

  private def doPostList(open: Boolean): UIO[Unit] =
    listOpen = open
    val sid  = sessionId.getOrElse("")
    val skip = empty.shouldDelete(sid)
    sessions.list
      .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
      .zip(Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
      .flatMap { (rows, now) =>
        listed = SessionIndex.touchCurrent(rows, sid, now, skip)
        post(HostMsg.SessionList(listed, sid, openPicker = open))
      }
  end doPostList

  private def respond(requestId: String, result: Json): UIO[Unit] =
    inbound.get(requestId) match
      case None     => ZIO.unit
      case Some(id) =>
        inbound -= requestId
        pendingPerm -= requestId
        absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.ok(id, result)))))

  private def requestKey(id: RpcId): String =
    id match
      case RpcId.Str(s) => s
      case RpcId.Num(n) => n.toString

  private def grokMethod(method: String): String =
    if method.startsWith("x.ai/") && !method.startsWith("_x.ai/") then s"_$method" else method

  private def notify(method: String, params: Json): UIO[Unit] =
    absorb(transport.write(Ndjson.encode(Rpc.toLine(Rpc.notify(method, params)))))

  private def rpc(method: String, params: Json, loadSessionId: Option[String] = None): UIO[Unit] =
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
      case Rpc.Notify("session/update", p) =>
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
          currentTurn = s"turn_$turnSeq"
        case _ =>
          if !loadCleared then loadCleared = true
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
    ZIO.foreachDiscard(SessionUpdate.hostMsgs(p, currentTurn)) { msg =>
      val out = msg match
        case HostMsg.AvailableCommands(cmds) => HostMsg.AvailableCommands(SessionCommands.merge(cmds))
        case other                           => other
      out match
        case m: HostMsg.SessionMeta => m.occupancy.foreach(o => occupancy = Some(o))
        case _                      => ()
      post(out)
    }

  private def ingestResponse(id: RpcId, result: Option[Json], error: Option[RpcError]): UIO[Unit] =
    ZIO.suspendSucceed {
      val method = pendingMethod.getOrElse(id, "")
      pendingMethod -= id
      val errPost =
        if method != "session/load" then error.map(e => post(HostMsg.Error(e.message))).getOrElse(ZIO.unit)
        else ZIO.unit
      errPost *> (method match
        case "initialize" =>
          rpc("session/new", SessionNewParams(cwd).asJson)
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
                      postMeta *> post(HostMsg.settings(settingsState)) *> doPostList(open = false)
                  }
        case "session/load" =>
          ingestLoad(id, result, error)
        case "session/prompt" =>
          val reason =
            result.flatMap(_.as[SessionPromptResult].toOption).map(_.stopReason).getOrElse("end_turn")
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
            val sid  = wanted.getOrElse("")
            post(HostMsg.SessionLocked(sid, SessionLoad.copy(kind))) *>
              (if kind == SessionLoadKind.Failed then post(HostMsg.Error(err.message)) else ZIO.unit)
          case None =>
            val clear = if !loadCleared then post(HostMsg.ClearTranscript) else ZIO.unit
            val snap  = ChatModel.snapshotTurns(loadModel.turns)
            loadModel = ChatModel.empty
            clear *>
              post(HostMsg.Transcript(snap)) *>
              ZIO.succeed(result.foreach(applySession)) *>
              ZIO.succeed {
                wanted.foreach { loadId =>
                  if sessionId.isEmpty then sessionId = Some(loadId)
                  empty.markHasHistory(sessionId.getOrElse(loadId))
                }
              } *> postMeta *> post(HostMsg.settings(settingsState)) *> doPostList(open = false)
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
      }
    }

  private def ingestUpdate(params: Json): UIO[Unit] =
    SessionState.decodeUpdate(params) match
      case Some(call: AcpUpdate.ToolCall)       => ingestTool(call.status, toBody(call))
      case Some(call: AcpUpdate.ToolCallUpdate) => ingestTool(call.status, toBody(call))
      case _                                    => ZIO.unit

  private def ingestTool(status: String, body: AcpToolCall): UIO[Unit] =
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

  private def toBody(call: AcpUpdate.ToolCall): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

  private def toBody(call: AcpUpdate.ToolCallUpdate): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

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

  private def postMeta: UIO[Unit] =
    val sid = sessionId.getOrElse("")
    sessions.list
      .catchAll(e => post(HostMsg.Error(e.message)).as(Nil))
      .flatMap { rows =>
        val named =
          rows
            .find(_.id == sid)
            .map(SessionIndex.displayTitle)
            .filter(_.nonEmpty)
            .getOrElse(title)
        post(HostMsg.SessionMeta(sid, named, modeId, modes, occupancy, modelId, models))
      }
  end postMeta

  private def jsonStr(json: Json, key: String): Option[String] =
    json match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case (k, Json.Str(s)) if k == key => s }
      case _ => None

  private def elicitCard(params: Json, requestId: String): ElicitCard =
    val mode   = jsonStr(params, "mode").filter(_ == "url").getOrElse("form")
    val title  = jsonStr(params, "message").orElse(jsonStr(params, "title")).getOrElse("Input required")
    val url    = jsonStr(params, "url")
    val server = jsonStr(params, "serverName").getOrElse("mcp")
    ElicitCard(requestId, server, mode, title, url)
end ChatRuntime

object ChatRuntime:
  val DefaultModes: List[ModeOption] = List(
    ModeOption("normal", "Normal"),
    ModeOption("auto", "Auto"),
    ModeOption("plan", "Plan"),
    ModeOption("always-approve", "Always approve"),
  )

  def make(
      transport: AcpTransport = AcpTransport.fake(),
      cwd: String = ".",
      capabilities: ClientCapabilities = ClientCapabilities.fake,
      fallbackSessionId: String = "sess_test",
      activeFile: () => Option[PromptChip] = () => None,
      includeActiveFile: () => Boolean = () => false,
      settings: () => SettingsState = () => SettingsState.defaults,
  ): ZIO[Scope & ChatEnv.Env, Nothing, ChatRuntime] =
    for
      host      <- ZIO.service[HostOut]
      sessions  <- ZIO.service[SessionRepo]
      mentions  <- ZIO.service[Mentions]
      persist   <- ZIO.service[ChangesPersist]
      copies    <- ZIO.service[TranscriptOut]
      review    <- ZIO.service[ReviewOps]
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
        gate,
        reentrant,
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
