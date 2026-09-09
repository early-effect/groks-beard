package groksbeard.core

import zio.json.ast.Json

final class Framed(val state: SessionState):
  private var buffer  = ""
  private var pending = Map.empty[String, (String, Option[ModeId])]

  def recordOutgoing(msg: Rpc): Unit =
    msg match
      case Rpc.Request(id, method, params) =>
        val mode =
          if AcpMethod.is(method, AcpMethod.SessionSetMode) then params.as[SessionSetModeParams].toOption.map(_.modeId)
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
            val op = AcpMethod.parse(method)
            if op.exists(SessionState.CommitBeforeContinue.contains) then mode.foreach(state.commitMode)
            else if op.contains(AcpMethod.SessionNew) || op.contains(AcpMethod.SessionLoad) then
              result.flatMap(SessionState.modeIdFromSessionResult).foreach(state.commitMode)
        }
      case Rpc.Notify(method, params) if SessionUpdate.isSessionNotify(method) =>
        SessionState.modeIdFromSessionUpdate(params).foreach(state.commitMode)
      case _ => ()
end Framed
