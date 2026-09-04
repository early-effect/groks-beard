package groksbeard.core

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
):
  private val framed                    = Framed(SessionState())
  private val store                     = ChangeStore()
  private var rpcId                     = 0
  private var turnSeq                   = 0
  private var currentTurn               = "turn_0"
  private var currentTitle              = "Untitled"
  private var sessionId: Option[String] = None
  private var modeId                    = "normal"
  private var modes: List[ModeOption]   = ChatRuntime.DefaultModes
  private val title                     = "Grok's Beard"
  private var running                   = false
  private var queued                    = 0
  private var chips                     = List.empty[PromptChip]
  private var pendingQueue              = Vector.empty[(String, List[PromptChip])]
  private var settingsState             = ChatRuntime.seedSettings(settings(), includeActiveFile)
  private var pendingPerm               = Map.empty[String, Json]
  private var inbound                   = Map.empty[String, RpcId]
  private var pendingMethod             = Map.empty[RpcId, String]

  transport.onData(chunk => this.synchronized { ingest(framed.feed(chunk)) })

  def state: SessionState = framed.state

  def close(): Unit = transport.close()

  def ready(): Unit = this.synchronized {
    post(HostMsg.Ready)
    rpc(
      "initialize",
      InitializeParams(1, capabilities, ClientInfo("groks-beard", title, "0.2.0")).asJson,
    )
  }

  def send(text: String): Unit = this.synchronized {
    val trimmed = text.trim
    val chosen  = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
    if trimmed.isEmpty && chosen.isEmpty then ()
    else if running then enqueue(trimmed, chosen)
    else
      chips = Nil
      runTurn(trimmed, chosen)
  }

  def queue(text: String): Unit = this.synchronized {
    val trimmed = text.trim
    val chosen  = PromptChip.chipsForSend(chips, activeFile(), settingsState.includeActiveFileByDefault)
    if trimmed.isEmpty && chosen.isEmpty then ()
    else enqueue(trimmed, chosen)
  }

  def cancel(): Unit = this.synchronized {
    queued = 0
    pendingQueue = Vector.empty
    running = false
    pendingPerm.keys.toList.foreach { id =>
      respond(id, Json.Obj("outcome" -> Json.Obj("outcome" -> Json.Str("cancelled"))))
    }
    post(HostMsg.Queued(0))
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

  def undo(path: String): Unit = this.synchronized {
    store.get(path) match
      case None       => ()
      case Some(file) =>
        val disk = ports.readDisk(file.path)
        ChangeSet.resolveUndo(file, disk, ports.confirmDirty(file.path)) match
          case UndoResolution.Apply(mutations) =>
            ports.applyUndo(mutations)
            store.drop(path)
            postChanges()
            post(HostMsg.ClearDiff)
          case UndoResolution.Disabled(reason) =>
            post(HostMsg.Error(s"Undo unavailable: $reason"))
          case UndoResolution.Cancelled =>
            ()
        end match
  }

  def closeDiff(): Unit = this.synchronized { post(HostMsg.ClearDiff) }

  def pendingChanges: List[FileChange] = this.synchronized { store.pending }

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
    pendingQueue = pendingQueue :+ (text, chosen)
    queued = pendingQueue.size
    post(HostMsg.Queued(queued))

  private def drainQueue(): Unit =
    if pendingQueue.isEmpty then queued = 0
    else
      val (text, chosen) = pendingQueue.head
      pendingQueue = pendingQueue.tail
      queued = pendingQueue.size
      post(HostMsg.Queued(queued))
      runTurn(text, chosen)

  private def runTurn(text: String, chosen: List[PromptChip]): Unit =
    running = true
    turnSeq += 1
    currentTurn = s"turn_$turnSeq"
    currentTitle = ChangeSet.turnTitle(if text.nonEmpty then text else chosen.headOption.map(_.path).getOrElse("chip"))
    post(HostMsg.UserMessage(currentTurn, text, chosen))
    val body = PromptChip.buildPromptText(text, chosen)
    val sid  = sessionId.getOrElse(fallbackSessionId)
    rpc("session/prompt", SessionPromptParams(sid, List(PromptText(text = body))).asJson)

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

  private def rpc(method: String, params: Json): Unit =
    rpcId += 1
    val id  = RpcId.Num(rpcId)
    val req = Rpc.Request(id, method, params)
    pendingMethod = pendingMethod.updated(id, method)
    framed.recordOutgoing(req)
    transport.write(Ndjson.encode(Rpc.toLine(req)))

  private def ingest(msgs: List[Rpc]): Unit =
    msgs.foreach {
      case Rpc.Notify("session/update", p) =>
        SessionUpdate.hostMsgs(p, currentTurn).foreach(post)
        ingestUpdate(p)
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
          case "elicitation/create" =>
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
        error.foreach(e => post(HostMsg.Error(e.message)))
        method match
          case "initialize" =>
            rpc("session/new", SessionNewParams(cwd).asJson)
          case "session/new" | "session/load" =>
            result.foreach(applySession)
            if sessionId.isEmpty then sessionId = Some(fallbackSessionId)
            postMeta()
            post(HostMsg.settings(settingsState))
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

  private def postMeta(): Unit =
    post(HostMsg.SessionMeta(sessionId.getOrElse(""), title, modeId, modes))

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
