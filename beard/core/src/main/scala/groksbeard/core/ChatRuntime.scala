package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final class ChatRuntime(
    post: HostMsg => Unit,
    agent: FakeAgent = FakeAgent(),
    ports: ReviewPorts = ReviewPorts.ignore,
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
  private var pendingPerm               = Map.empty[String, Json]

  def state: SessionState = framed.state

  def ready(): Unit =
    post(HostMsg.Ready)
    call("initialize", Json.Obj("protocolVersion" -> Json.Num(1)))
    val result = call(
      "session/new",
      Json.Obj("cwd" -> Json.Str("."), "mcpServers" -> Json.Arr()),
    )
    result.foreach { json =>
      json match
        case obj: Json.Obj =>
          obj.get("sessionId") match
            case Some(Json.Str(id)) => sessionId = Some(id)
            case _                  => ()
          SessionState.modeIdFromSessionResult(json).foreach { id =>
            modeId = id
            framed.state.commitMode(id)
          }
          modes = parseModes(obj).getOrElse(modes)
        case _ => ()
    }
    if sessionId.isEmpty then sessionId = Some(agent.sessionId)
    postMeta()
    post(HostMsg.Settings(SettingsState.defaults))
  end ready

  def send(text: String): Unit =
    val trimmed = text.trim
    if trimmed.isEmpty then ()
    else if running then
      queued += 1
      post(HostMsg.Queued(queued))
    else runTurn(trimmed)

  def queue(text: String): Unit =
    if text.trim.nonEmpty then
      queued += 1
      post(HostMsg.Queued(queued))

  def cancel(): Unit =
    queued = 0
    running = false
    post(HostMsg.Queued(0))

  def setMode(id: String): Unit =
    val sid = sessionId.getOrElse(agent.sessionId)
    call("session/set_mode", Json.Obj("sessionId" -> Json.Str(sid), "modeId" -> Json.Str(id)))
    modeId = id
    framed.state.commitMode(id)
    postMeta()

  def openDiff(requestId: String): Unit =
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
          case None       => openChanges()

  def openChanges(): Unit =
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
  end openChanges

  def keep(path: String): Unit =
    store.keep(path)
    postChanges()
    if store.get(path).isEmpty then post(HostMsg.ClearDiff)

  def undo(path: String): Unit =
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

  def closeDiff(): Unit =
    post(HostMsg.ClearDiff)

  def pendingChanges: List[FileChange] = store.pending

  private def runTurn(text: String): Unit =
    running = true
    turnSeq += 1
    currentTurn = s"turn_$turnSeq"
    currentTitle = ChangeSet.turnTitle(text)
    post(HostMsg.UserMessage(currentTurn, text))
    val sid    = sessionId.getOrElse(agent.sessionId)
    val result = call(
      "session/prompt",
      Json.Obj(
        "sessionId" -> Json.Str(sid),
        "prompt"    -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))),
      ),
    )
    val reason = result.flatMap(stopReason).getOrElse("end_turn")
    post(HostMsg.TurnEnd(currentTurn, reason))
    running = false
  end runTurn

  private def call(method: String, params: Json): Option[Json] =
    rpcId += 1
    val id  = Json.Num(rpcId)
    val req = Rpc.Request(id, method, params)
    framed.recordOutgoing(req)
    val msgs              = framed.feed(agent.encodeReplies(req))
    var out: Option[Json] = None
    msgs.foreach {
      case Rpc.Notify("session/update", p) =>
        SessionUpdate.hostMsgs(p, currentTurn).foreach(post)
        ingestUpdate(p)
      case Rpc.Request(rid, "session/request_permission", params) =>
        val reqId = rid match
          case Json.Str(s) => s
          case other       => other.toJson
        pendingPerm = pendingPerm.updated(reqId, params)
        post(HostMsg.Permission(DiffContent.permissionCard(params, reqId)))
      case Rpc.Response(rid, result, error) if rid == id =>
        error.foreach(e => post(HostMsg.Error(e.message)))
        out = result
      case _ => ()
    }
    out
  end call

  private def ingestUpdate(params: Json): Unit =
    val update = SessionState.unwrapUpdate(params)
    update.get("sessionUpdate") match
      case Some(Json.Str("tool_call")) | Some(Json.Str("tool_call_update")) =>
        val status = update.get("status") match
          case Some(Json.Str(s)) => s
          case _                 => "pending"
        val diffs = DiffContent.reconstruct(update, ports.readDisk, DiffContent.diskIsBefore(status))
        if diffs.nonEmpty then
          store.ingest(
            sessionId.getOrElse(agent.sessionId),
            currentTurn,
            currentTitle,
            diffs.map(DiffContent.fileChangeFrom),
          )
          postChanges()
      case _ => ()
    end match
  end ingestUpdate

  private def postChanges(): Unit =
    post(HostMsg.Changes(store.summary))
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

  private def stopReason(result: Json): Option[String] =
    result match
      case obj: Json.Obj =>
        obj.get("stopReason") match
          case Some(Json.Str(s)) => Some(s)
          case _                 => None
      case _ => None

  private def parseModes(obj: Json.Obj): Option[List[ModeOption]] =
    obj.get("modes") match
      case Some(modesObj: Json.Obj) =>
        modesObj.get("availableModes") match
          case Some(Json.Arr(items)) =>
            val list = items.toList.collect { case o: Json.Obj =>
              (o.get("id"), o.get("name")) match
                case (Some(Json.Str(id)), Some(Json.Str(name))) => Some(ModeOption(id, name))
                case (Some(Json.Str(id)), _)                    => Some(ModeOption(id, id))
                case _                                          => None
            }.flatten
            if list.isEmpty then None else Some(list)
          case _ => None
      case _ => None
end ChatRuntime

object ChatRuntime:
  val DefaultModes: List[ModeOption] = List(
    ModeOption("normal", "Normal"),
    ModeOption("auto", "Auto"),
    ModeOption("plan", "Plan"),
    ModeOption("always-approve", "Always approve"),
  )
end ChatRuntime
