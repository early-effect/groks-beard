package groksbeard.core

import zio.json.ast.Json

final case class FrameState(
    buffer: String = "",
    pending: Map[String, (AcpMethod, Option[ModeId])] = Map.empty,
    modeId: Option[ModeId] = None,
):
  def planActive: Boolean = modeId.exists(_.isPlan)

  def commitMode(id: ModeId): FrameState =
    copy(modeId = Some(id))

object FrameState:
  val empty: FrameState = FrameState()

  def recordOutgoing(state: FrameState, msg: Rpc): FrameState =
    msg match
      case Rpc.Request(id, method, params) =>
        AcpMethod.parse(method) match
          case None     => state
          case Some(op) =>
            val mode =
              if op == AcpMethod.SessionSetMode then params.as[SessionSetModeParams].toOption.map(_.modeId)
              else None
            state.copy(pending = state.pending.updated(RpcId.key(id), (op, mode)))
      case _ => state

  def feed(state: FrameState, chunk: String): (FrameState, List[Rpc]) =
    val (lines, rest) = Ndjson.split(state.buffer, chunk)
    val start         = state.copy(buffer = rest)
    lines.foldLeft((start, List.empty[Rpc])) { case ((st, acc), line) =>
      Rpc.parse(line) match
        case Right(msg) => (commit(st, msg), acc :+ msg)
        case Left(_)    => (st, acc)
    }

  private def commit(state: FrameState, msg: Rpc): FrameState =
    msg match
      case Rpc.Response(id, result, error) =>
        val key      = RpcId.key(id)
        val recorded = state.pending.get(key)
        val dropped  = state.copy(pending = state.pending - key)
        if error.nonEmpty then dropped
        else
          recorded match
            case None                 => dropped
            case Some((method, mode)) =>
              if SessionState.CommitBeforeContinue.contains(method) then mode.fold(dropped)(dropped.commitMode)
              else if method == AcpMethod.SessionNew || method == AcpMethod.SessionLoad then
                result.flatMap(SessionState.modeIdFromSessionResult).fold(dropped)(dropped.commitMode)
              else dropped
      case Rpc.Notify(method, params) if SessionUpdate.isSessionNotify(method) =>
        SessionState.modeIdFromSessionUpdate(params).fold(state)(state.commitMode)
      case _ => state
end FrameState
