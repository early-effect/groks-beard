package groksbeard.core

import zio.json.*
import zio.json.ast.Json

/** Compact fold of `updates.jsonl` (or a live ACP replay) into a transcript snapshot.
  *
  * Thoughts and tool bodies stay off the model. Huge `tool_call_update` lines are scanned for identity/status instead
  * of parsed as JSON.
  */
final case class SessionSnapshot(
    turns: List[TurnView] = Nil,
    todos: List[TodoEntry] = Nil,
    tasks: List[TaskRow] = Nil,
)

object SessionLog:
  val HeavyBytes: Int = 8192

  final case class State(
      turnSeq: Int = 0,
      currentTurn: TurnId = TurnId.mint(0),
      userOpen: Boolean = false,
      model: ChatModel = ChatModel.empty,
  )

  val empty: State = State()

  def foldLine(state: State, line: String): State =
    val t = line.trim
    if t.isEmpty then state
    else if t.indexOf("agent_thought_chunk") >= 0 then state
    else if t.indexOf("tool_call_update") >= 0 then scanTool(state, t)
    else parseLine(state, t)

  def finish(state: State): SessionSnapshot =
    SessionSnapshot(
      turns = ChatModel.snapshotTurns(state.model.turns),
      todos = state.model.todos,
      tasks = state.model.tasks,
    )

  def fold(lines: Iterable[String]): SessionSnapshot =
    finish(lines.foldLeft(empty)(foldLine))

  private def parseLine(state: State, line: String): State =
    line.fromJson[Json] match
      case Left(_)     => state
      case Right(json) =>
        val method = str(json, "method")
        val params = field(json, "params")
        (method, params) match
          case (Some(m), Some(p)) if AcpMethod.isSessionNotify(m) => foldUpdate(state, p)
          case _                                                  => state

  private def foldUpdate(state: State, params: Json): State =
    SessionState.decodeUpdate(params) match
      case None =>
        Tasks.fold(params, state.model.tasks) match
          case Some(tasks) => state.copy(model = state.model.copy(tasks = tasks))
          case None        => state
      case Some(_: AcpUpdate.Thought) =>
        state
      case Some(_: AcpUpdate.Commands | _: AcpUpdate.CurrentMode | _: AcpUpdate.Usage | _: AcpUpdate.ConfigOptions) =>
        state
      case Some(_: AcpUpdate.User) =>
        val next = noteUser(state)
        applyHost(next, SessionUpdate.hostMsgs(params, next.currentTurn))
      case Some(_: AcpUpdate.Agent) =>
        val next = closeUser(state)
        applyHost(next, SessionUpdate.hostMsgs(params, next.currentTurn))
      case Some(call: AcpUpdate.ToolCall) =>
        val next = closeUser(state)
        applyHost(next, List(HostMsg.ToolCall(next.currentTurn, compactCall(call))))
      case Some(call: AcpUpdate.ToolCallUpdate) =>
        val next = closeUser(state)
        applyHost(next, List(HostMsg.ToolCall(next.currentTurn, compactUpdate(call))))
      case Some(AcpUpdate.Plan(entries)) =>
        applyHost(state, List(HostMsg.Todos(Todos.fromEntries(entries))))
      case Some(AcpUpdate.TurnCompleted(reason)) =>
        val next = closeUser(state)
        applyHost(next, List(HostMsg.TurnEnd(next.currentTurn, reason)))

  private def scanTool(state: State, line: String): State =
    scanField(line, "toolCallId") match
      case None     => state
      case Some(id) =>
        val next = closeUser(state)
        val row  =
          ToolRow(
            ToolCallId(id),
            title = scanField(line, "title").filter(_.nonEmpty).getOrElse(""),
            kind = scanField(line, "kind").map(ToolKind.fromWire).getOrElse(ToolKind.Other),
            status = scanField(line, "status").map(ToolStatus.fromWire).getOrElse(ToolStatus.Pending),
          )
        applyHost(next, List(HostMsg.ToolCall(next.currentTurn, row)))

  private def compactCall(call: AcpUpdate.ToolCall): ToolRow =
    val loc = call.locations.headOption
    ToolRow(
      call.toolCallId,
      title = if call.title.nonEmpty then call.title else "Tool",
      kind = call.kind,
      status = call.status,
      path = loc.map(_.path),
      line = loc.flatMap(_.line),
    )
  end compactCall

  private def compactUpdate(call: AcpUpdate.ToolCallUpdate): ToolRow =
    val loc = call.locations.headOption
    ToolRow(
      call.toolCallId,
      title = call.title,
      kind = call.kind,
      status = call.status,
      path = loc.map(_.path),
      line = loc.flatMap(_.line),
    )
  end compactUpdate

  private def noteUser(state: State): State =
    if state.userOpen then state
    else
      val n = state.turnSeq + 1
      state.copy(turnSeq = n, currentTurn = TurnId.mint(n), userOpen = true)

  private def closeUser(state: State): State =
    state.copy(userOpen = false)

  private def applyHost(state: State, msgs: List[HostMsg]): State =
    state.copy(model = msgs.foldLeft(state.model)(ChatModel.applyMsg))

  private def scanField(line: String, key: String): Option[String] =
    val tight  = s""""$key":""""
    val spaced = s""""$key": """"
    val at     =
      val i = line.indexOf(tight)
      if i >= 0 then Some(i + tight.length)
      else
        val j = line.indexOf(spaced)
        if j >= 0 then Some(j + spaced.length) else None
    at.flatMap { start =>
      val end = line.indexOf('"', start)
      if end <= start then None else Some(line.substring(start, end))
    }
  end scanField

  private def str(json: Json, key: String): Option[String] =
    field(json, key).collect { case Json.Str(s) => s }

  private def field(json: Json, key: String): Option[Json] =
    json match
      case obj: Json.Obj => obj.fields.collectFirst { case (k, v) if k == key => v }
      case _             => None
end SessionLog
