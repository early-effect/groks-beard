package groksbeard.core

import zio.json.ast.Json

final class Framed(val state: SessionState):
  private var buffer  = ""
  private var pending = Map.empty[String, (String, Option[String])]

  def recordOutgoing(msg: Rpc): Unit =
    msg match
      case Rpc.Request(id, method, params) =>
        val mode =
          if method == "session/set_mode" then params.as[SessionSetModeParams].toOption.map(_.modeId)
          else None
        pending = pending.updated(RpcId.key(id), (method, mode))
      case _ => ()

  def feed(chunk: String): List[Rpc] =
    val (lines, rest) = Ndjson.split(buffer, chunk)
    buffer = rest
    lines.flatMap { line =>
      Rpc.parse(line) match
        case Right(msg) =>
          commit(msg)
          Some(msg)
        case Left(_) => None
    }
  end feed

  private def commit(msg: Rpc): Unit =
    msg match
      case Rpc.Response(id, result, error) =>
        val key      = RpcId.key(id)
        val recorded = pending.get(key)
        pending = pending - key
        val ok = error.isEmpty
        recorded.foreach { (method, mode) =>
          if ok then
            if SessionState.CommitBeforeContinue.contains(method) then mode.foreach(state.commitMode)
            else if method == "session/new" || method == "session/load" then
              result.flatMap(SessionState.modeIdFromSessionResult).foreach(state.commitMode)
        }
      case Rpc.Notify(method, params) if method == "session/update" =>
        SessionState.modeIdFromSessionUpdate(params).foreach(state.commitMode)
      case _ => ()
end Framed
