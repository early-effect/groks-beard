package groksbeard.core

import zio.*
import zio.json.*
import zio.json.ast.Json

final class ChatRuntime(
    post: HostMsg => Unit,
    transport: AcpTransport = AcpTransport.fake(),
    ports: ReviewPorts = ReviewPorts.ignore,
    cwd: String = ".",
    capabilities: ClientCapabilities = ClientCapabilities.fake,
    fallbackSessionId: String = "sess_test",
    searchFiles: String => List[MentionFile] = _ => Nil,
    activeFile: () => Option[PromptChip] = () => None,
    includeActiveFile: () => Boolean = () => false,
    settings: () => SettingsState = () => SettingsState.defaults,
    listSessions: () => List[SessionRow] = () => Nil,
    scheduleEmptyDelete: String => Unit = _ => (),
    renameOnDisk: (String, RenameOp) => Option[SessionRow] = (_, _) => None,
    deleteOnDisk: String => Boolean = _ => false,
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

  transport.onData { chunk =>
    val epoch = inboundEpoch.get()
    val msgs  = this.synchronized { framed.feed(chunk) }
    msgs.foreach { msg =>
      if inboundEpoch.get() == epoch then this.synchronized { ingest(List(msg)) }
    }
  }

  def state: SessionState = framed.state

  def close(): Unit = transport.close()

  def noteAgentLine(line: String): Unit = this.synchronized {
    AgentLog.classify(line) match
      case Some(msg) if running =>
        post(HostMsg.Error(msg))
        cancel()
      case _ => ()
  }

  def ready(): Unit = this.synchronized {
    post(HostMsg.Ready)
    rpc(
      "initialize",
      InitializeParams(1, capabilities, ClientInfo("groks-beard", title, "0.2.0")).asJson,
    )
  }

  def send(text: String): Unit = this.synchronized {
    val trimmed = text.trim
    SessionCommands.intercept(trimmed) match
      case Some(cmd) if SessionCommands.isNew(cmd.name) =>
        newSession()
      case Some(cmd) if SessionCommands.isResume(cmd.name) || SessionCommands.isHome(cmd.name) =>
        openPicker()
      case Some(cmd) if SessionCommands.isModel(cmd.name) =>
        if cmd.args.isEmpty then ()
        else
          ModelOption.pick(cmd.args, models) match
            case Some(m) => setModel(m.modelId)
            case None    => post(HostMsg.Error(s"Unknown model: ${cmd.args}"))
      case Some(cmd) if SessionCommands.isRename(cmd.name) =>
        SessionEdit.parseRename(cmd.args) match
          case Left("empty") => ()
          case Left(err)     => post(HostMsg.Error(err))
          case Right(op)     => renameSession(sessionId.getOrElse(""), op)
      case Some(cmd) if SessionCommands.isDelete(cmd.name) =>
        ()
      case _ =>
        val chosen = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
        if trimmed.isEmpty && chosen.isEmpty then ()
        else if running then enqueue(trimmed, chosen)
        else
          chips = Nil
          runTurn(trimmed, chosen)
    end match
  }

  def queue(text: String): Unit = this.synchronized {
    val trimmed = text.trim
    val chosen  = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
    if trimmed.isEmpty && chosen.isEmpty then ()
    else enqueue(trimmed, chosen)
  }

  def cancel(): Unit = this.synchronized {
    pendingPerm.keys.toList.foreach { id =>
      respond(id, Json.Obj("outcome" -> Json.Obj("outcome" -> Json.Str("cancelled"))))
    }
    val sid = sessionId.getOrElse(fallbackSessionId)
    notify("session/cancel", SessionCancelParams(sid).asJson)
    if running then
      running = false
      post(HostMsg.TurnEnd(currentTurn, "cancelled"))
    drainQueue()
  }

  def addChip(chip: PromptChip): Unit = this.synchronized {
    chips = PromptChip.upsert(chips, chip)
    post(HostMsg.chip(chip))
  }

  def removeChip(absPath: String, startLine: Option[Int], endLine: Option[Int]): Unit = this.synchronized {
    chips = chips.filterNot { c =>
      c.absPath == absPath && c.startLine == startLine && c.endLine == endLine
    }
  }

  def currentSettings: SettingsState = this.synchronized { settingsState }

  def replaceSettings(next: SettingsState): Unit = this.synchronized {
    settingsState = next
    post(HostMsg.settings(settingsState))
  }

  def setSetting(key: String, value: String | Boolean): Unit = this.synchronized {
    settingsState = ChatRuntime.patchSettings(settingsState, key, value)
    post(HostMsg.settings(settingsState))
  }

  def mentionQuery(query: String): Unit = this.synchronized {
    post(HostMsg.MentionResults(query, searchFiles(query)))
  }

  def mentionPick(path: String, absPath: String): Unit = this.synchronized {
    addChip(PromptChip(path, absPath, source = "mention"))
  }

  def permissionChoice(requestId: String, optionId: String): Unit = this.synchronized {
    respond(
      requestId,
      Json.Obj(
        "outcome" -> Json.Obj("outcome" -> Json.Str("selected"), "optionId" -> Json.Str(optionId))
      ),
    )
  }

  def planVerdict(requestId: String, verdict: String): Unit = this.synchronized {
    respond(requestId, Json.Obj("outcome" -> Json.Str(verdict)))
  }

  def questionChoice(requestId: String, questionId: String, optionId: String): Unit = this.synchronized {
    respond(
      requestId,
      Json.Obj(
        "answers" -> Json.Arr(
          Json.Obj("questionId" -> Json.Str(questionId), "optionId" -> Json.Str(optionId))
        )
      ),
    )
  }

  def questionDismiss(requestId: String): Unit = this.synchronized {
    respond(requestId, Json.Obj("answers" -> Json.Arr()))
  }

  def elicitAccept(requestId: String): Unit = this.synchronized {
    respond(requestId, Json.Obj("action" -> Json.Str("accept")))
  }

  def elicitDecline(requestId: String): Unit = this.synchronized {
    respond(requestId, Json.Obj("action" -> Json.Str("decline")))
  }

  def setMode(id: String): Unit = this.synchronized {
    val sid = sessionId.getOrElse(fallbackSessionId)
    rpc("session/set_mode", SessionSetModeParams(sid, id).asJson)
    modeId = id
    framed.state.commitMode(id)
    postMeta()
  }

  def cycleMode(): Unit = this.synchronized {
    setMode(ModeLabel.nextMode(modeId, modes))
  }

  def setModel(id: String): Unit = this.synchronized {
    if id.isEmpty then ()
    else
      val sid = sessionId.getOrElse(fallbackSessionId)
      rpc("session/set_model", SessionSetModelParams(sid, id).asJson)
      modelId = id
      postMeta()
  }

  def openDiff(requestId: String): Unit = this.synchronized {
    pendingPerm.get(requestId) match
      case Some(params) =>
        val diffs = DiffContent.reconstruct(
          DiffContent.toolCallFromPermission(params),
          ports.readDisk,
          diskIsBeforeWrite = true,
        )
        showDiffs(ChangeSet.turnTitle(currentTitle), diffs)
      case None =>
        store.pending.find(f => f.toolCallId == requestId || f.path == requestId) match
          case Some(file) => showFile(file)
          case None       => openChangesUnlocked()
  }

  def openChanges(): Unit = this.synchronized { openChangesUnlocked() }

  def keep(path: String): Unit = this.synchronized {
    store.keep(path)
    postChanges()
    if store.get(path).isEmpty then post(HostMsg.ClearDiff)
  }

  def keepTurn(turnId: String): Unit = this.synchronized {
    store.keepTurn(turnId)
    postChanges()
    post(HostMsg.ClearDiff)
  }

  def keepAll(): Unit = this.synchronized {
    store.keepAll()
    postChanges()
    post(HostMsg.ClearDiff)
  }

  def undo(path: String): Unit = this.synchronized {
    store.get(path).foreach(undoFile)
  }

  def undoTurn(turnId: String): Unit = this.synchronized {
    undoFiles(store.filesOf(turnId))
  }

  def undoAll(): Unit = this.synchronized {
    undoFiles(store.pending)
  }

  def closeDiff(): Unit = this.synchronized { post(HostMsg.ClearDiff) }

  def pendingChanges: List[FileChange] = this.synchronized { store.pending }

  def pendingSets: List[ChangeSet] = this.synchronized { store.list }

  def slashPick(name: String): Unit = this.synchronized {
    if SessionCommands.isNew(name) then newSession()
    else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then openPicker()
  }

  def focusedId: Option[String] = this.synchronized { sessionId }

  def focusedTitle: String = this.synchronized {
    sessionId.map(displayTitleOf).filter(t => t.nonEmpty && t != title).getOrElse("")
  }

  def renameSession(id: String, op: RenameOp): Unit = this.synchronized {
    val target = if id.nonEmpty then id else sessionId.getOrElse("")
    if target.isEmpty then post(HostMsg.Error("No session to rename"))
    else
      op match
        case RenameOp.Manual(t) if t.trim.isEmpty =>
          post(HostMsg.Error("Session title cannot be empty"))
        case _ =>
          renameOnDisk(target, op) match
            case None =>
              post(HostMsg.Error("Could not rename session"))
            case Some(_) =>
              postMeta()
              postList(listOpen)
    end if
  }

  def deleteSession(id: String): Unit = this.synchronized {
    val target = if id.nonEmpty then id else sessionId.getOrElse("")
    if target.isEmpty then post(HostMsg.Error("No session to delete"))
    else if !deleteOnDisk(target) then post(HostMsg.Error("Could not delete session"))
    else if sessionId.contains(target) then newSession()
    else postList(listOpen)
  }

  def newSession(): Unit = this.synchronized {
    inboundEpoch.incrementAndGet()
    pendingResume.foreach(id => cancelledLoads += id)
    leaveCurrent()
    resetLocal()
    sessionId = None
    post(HostMsg.ClearTranscript)
    rpc("session/new", SessionNewParams(cwd).asJson)
  }

  def resumeSession(id: String): Unit = this.synchronized {
    if id.isEmpty then openPicker()
    else if sessionId.contains(id) then postList(open = false)
    else
      inboundEpoch.incrementAndGet()
      pendingResume.foreach(prev => cancelledLoads += prev)
      leaveCurrent()
      cancelledLoads -= id
      resetTurnState()
      sessionId = Some(id)
      loading = true
      loadCleared = true
      pendingResume = Some(id)
      post(HostMsg.ClearTranscript)
      postMeta()
      postList(open = false)
      rpc("session/load", SessionLoadParams(id, cwd).asJson, loadSessionId = Some(id))
  }

  def openPicker(): Unit = this.synchronized { postList(open = true) }

  def closePicker(): Unit = this.synchronized { postList(open = false) }

  private def openChangesUnlocked(): Unit =
    val files = store.pending
    if files.isEmpty then ()
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

  private def enqueue(text: String, chosen: List[PromptChip]): Unit =
    chips = Nil
    queueSeq += 1
    pendingQueue = pendingQueue :+ QueuedPrompt(s"q$queueSeq", text, chosen)
    postQueue()

  private def drainQueue(): Unit =
    if pendingQueue.isEmpty then postQueue()
    else
      val item = pendingQueue.head
      pendingQueue = pendingQueue.tail
      postQueue()
      runTurn(item.text, item.chips)

  private def postQueue(): Unit =
    post(HostMsg.Queued(pendingQueue.toList))

  private def runTurn(text: String, chosen: List[PromptChip]): Unit =
    running = true
    turnSeq += 1
    currentTurn = s"turn_$turnSeq"
    currentTitle = ChangeSet.turnTitle(if text.nonEmpty then text else chosen.headOption.map(_.path).getOrElse("chip"))
    sessionId.foreach(empty.markHasHistory)
    post(HostMsg.UserMessage(currentTurn, text, chosen))
    val body = PromptChip.buildPromptText(text, chosen)
    val sid  = sessionId.getOrElse(fallbackSessionId)
    rpc("session/prompt", SessionPromptParams(sid, List(PromptText(text = body))).asJson)
  end runTurn

  private def leaveCurrent(): Unit =
    sessionId.foreach { id =>
      if empty.shouldDelete(id) then scheduleEmptyDelete(id)
      empty.forget(id)
    }

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

  private def currentMs(): Long =
    Unsafe.unsafe { implicit u =>
      Clock.ClockLive.unsafe.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
    }

  private def postList(open: Boolean): Unit =
    listOpen = open
    val sid  = sessionId.getOrElse("")
    val rows = SessionIndex.touchCurrent(listSessions(), sid, currentMs(), empty.shouldDelete(sid))
    post(HostMsg.SessionList(rows, sid, openPicker = open))

  private def respond(requestId: String, result: Json): Unit =
    inbound.get(requestId).foreach { id =>
      inbound -= requestId
      pendingPerm -= requestId
      transport.write(Ndjson.encode(Rpc.toLine(Rpc.ok(id, result))))
    }

  private def requestKey(id: RpcId): String =
    id match
      case RpcId.Str(s) => s
      case RpcId.Num(n) => n.toString

  private def grokMethod(method: String): String =
    if method.startsWith("x.ai/") && !method.startsWith("_x.ai/") then s"_$method" else method

  private def notify(method: String, params: Json): Unit =
    transport.write(Ndjson.encode(Rpc.toLine(Rpc.notify(method, params))))

  private def rpc(method: String, params: Json, loadSessionId: Option[String] = None): RpcId =
    rpcId += 1
    val id  = RpcId.Num(rpcId)
    val req = Rpc.Request(id, method, params)
    pendingMethod = pendingMethod.updated(id, method)
    loadSessionId.foreach(sid => pendingLoad = pendingLoad.updated(id, sid))
    framed.recordOutgoing(req)
    transport.write(Ndjson.encode(Rpc.toLine(req)))
    id

  private def ingest(msgs: List[Rpc]): Unit =
    msgs.foreach {
      case Rpc.Notify("session/update", p) =>
        val updateSid = SessionState.decodeNotify(p).map(_.sessionId).filter(_.nonEmpty)
        val stale     =
          updateSid.exists(cancelledLoads.contains) ||
            updateSid.exists(sid => sessionId.exists(_ != sid))
        if stale then ()
        else
          if loading then
            SessionState.decodeUpdate(p) match
              case Some(_: AcpUpdate.User) =>
                if !loadCleared then loadCleared = true
                turnSeq += 1
                currentTurn = s"turn_$turnSeq"
              case _ =>
                if !loadCleared then loadCleared = true
            SessionUpdate.hostMsgs(p, currentTurn).foreach { msg =>
              msg match
                case HostMsg.AvailableCommands(cmds) =>
                  post(HostMsg.AvailableCommands(SessionCommands.merge(cmds)))
                case other =>
                  other match
                    case m: HostMsg.SessionMeta => m.occupancy.foreach(o => occupancy = Some(o))
                    case _                      => ()
                  loadModel = ChatModel.applyMsg(loadModel, other)
            }
          else
            SessionUpdate.hostMsgs(p, currentTurn).foreach { msg =>
              val out = msg match
                case HostMsg.AvailableCommands(cmds) => HostMsg.AvailableCommands(SessionCommands.merge(cmds))
                case other                           => other
              out match
                case m: HostMsg.SessionMeta => m.occupancy.foreach(o => occupancy = Some(o))
                case _                      => ()
              post(out)
            }
          end if
          ingestUpdate(p)
        end if
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
            transport.write(
              Ndjson.encode(Rpc.toLine(Rpc.fail(rid, Rpc.MethodNotFound, s"Method not found: $rawMethod")))
            )
        end match
      case Rpc.Response(id, result, error) =>
        val method = pendingMethod.getOrElse(id, "")
        pendingMethod -= id
        if method != "session/load" then error.foreach(e => post(HostMsg.Error(e.message)))
        method match
          case "initialize" =>
            rpc("session/new", SessionNewParams(cwd).asJson)
          case "session/new" =>
            result.foreach { json =>
              json.as[SessionNewResult].foreach { decoded =>
                empty.markCreated(decoded.sessionId)
                val steal =
                  pendingResume.nonEmpty || sessionId.exists(cur => cur.nonEmpty && cur != decoded.sessionId)
                if steal then
                  if empty.shouldDelete(decoded.sessionId) then scheduleEmptyDelete(decoded.sessionId)
                else
                  applySession(json)
                  if sessionId.isEmpty then sessionId = Some(fallbackSessionId)
                  postMeta()
                  post(HostMsg.settings(settingsState))
                  postList(open = false)
              }
            }
          case "session/load" =>
            val loadSid = pendingLoad.get(id)
            pendingLoad -= id
            val stale =
              loadSid.exists(cancelledLoads.contains) ||
                pendingResume.exists(want => loadSid.exists(_ != want)) ||
                pendingResume.isEmpty
            if stale then ()
            else
              loading = false
              val wanted = pendingResume
              pendingResume = None
              error match
                case Some(err) =>
                  val kind = SessionLoad.classify(err.message)
                  val sid  = wanted.getOrElse("")
                  post(HostMsg.SessionLocked(sid, SessionLoad.copy(kind)))
                  if kind == SessionLoadKind.Failed then post(HostMsg.Error(err.message))
                case None =>
                  if !loadCleared then post(HostMsg.ClearTranscript)
                  val snap = ChatModel.snapshotTurns(loadModel.turns)
                  loadModel = ChatModel.empty
                  post(HostMsg.Transcript(snap))
                  result.foreach(applySession)
                  wanted.foreach { loadId =>
                    if sessionId.isEmpty then sessionId = Some(loadId)
                    empty.markHasHistory(sessionId.getOrElse(loadId))
                  }
                  postMeta()
                  post(HostMsg.settings(settingsState))
                  postList(open = false)
              end match
            end if
          case "session/prompt" =>
            val reason =
              result.flatMap(_.as[SessionPromptResult].toOption).map(_.stopReason).getOrElse("end_turn")
            post(HostMsg.TurnEnd(currentTurn, reason))
            running = false
            drainQueue()
          case _ => ()
        end match
      case _ => ()
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

  private def ingestUpdate(params: Json): Unit =
    SessionState.decodeUpdate(params) match
      case Some(call: AcpUpdate.ToolCall) =>
        ingestTool(call.status, toBody(call))
      case Some(call: AcpUpdate.ToolCallUpdate) =>
        ingestTool(call.status, toBody(call))
      case _ => ()

  private def ingestTool(status: String, body: AcpToolCall): Unit =
    val diffs = DiffContent.reconstruct(body.asJson, ports.readDisk, DiffContent.diskIsBefore(status))
    if diffs.nonEmpty then
      store.ingest(
        sessionId.getOrElse(fallbackSessionId),
        currentTurn,
        currentTitle,
        diffs.map(DiffContent.fileChangeFrom),
      )
      postChanges()
  end ingestTool

  private def toBody(call: AcpUpdate.ToolCall): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

  private def toBody(call: AcpUpdate.ToolCallUpdate): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

  private def undoFile(file: FileChange): Boolean =
    val disk = ports.readDisk(file.path)
    ChangeSet.resolveUndo(file, disk, ports.confirmDirty(file.path)) match
      case UndoResolution.Apply(mutations) =>
        ports.applyUndo(mutations)
        store.drop(file.path)
        true
      case UndoResolution.Disabled(reason) =>
        post(HostMsg.Error(s"Undo unavailable: $reason"))
        false
      case UndoResolution.Cancelled =>
        false
    end match
  end undoFile

  private def undoFiles(files: List[FileChange]): Unit =
    val _ = files.takeWhile(undoFile)
    postChanges()
    post(HostMsg.ClearDiff)

  private def postChanges(): Unit =
    post(HostMsg.changes(store.summary))
    ports.onStoreChange()

  private def showFile(file: FileChange): Unit =
    val oldText = file.oldSnapshot.getOrElse("")
    val newText = file.newSnapshot.getOrElse("")
    post(HostMsg.DiffPreview(file.path, oldText, newText, file.wholeFile))
    ports.openNativeDiffs(UnifiedDiff.fileName(file.path), List(DiffPair(file.path, oldText, newText, file.wholeFile)))

  private def showDiffs(heading: String, diffs: List[ReconstructedFileDiff]): Unit =
    diffs.headOption.foreach { d =>
      post(HostMsg.DiffPreview(d.path, d.oldText, d.newText, d.wholeFile))
    }
    val pairs = diffs.map(d => DiffPair(d.path, d.oldText, d.newText, d.wholeFile))
    if pairs.nonEmpty then ports.openNativeDiffs(heading, pairs)

  private def displayTitleOf(id: String): String =
    listSessions()
      .find(_.id == id)
      .map(SessionIndex.displayTitle)
      .filter(_.nonEmpty)
      .getOrElse(title)

  private def postMeta(): Unit =
    val sid = sessionId.getOrElse("")
    post(HostMsg.SessionMeta(sid, displayTitleOf(sid), modeId, modes, occupancy, modelId, models))

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
