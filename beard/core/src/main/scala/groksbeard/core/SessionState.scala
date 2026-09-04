package groksbeard.core

import zio.json.ast.Json

final class SessionState:
  var modeId: Option[String] = None
  var planActive: Boolean    = false

  def commitMode(id: String): Unit =
    modeId = Some(id)
    planActive = id.toLowerCase.contains("plan")

object SessionState:
  val CommitBeforeContinue: Set[String] = Set("session/set_mode")

  def modeIdFromSessionResult(result: Json): Option[String] =
    result
      .as[SessionNewResult]
      .toOption
      .flatMap(_.modes)
      .map(_.currentModeId)
      .filter(_.nonEmpty)

  def modeIdFromSessionUpdate(params: Json): Option[String] =
    decodeUpdate(params).collect { case AcpUpdate.CurrentMode(modeId, currentModeId) =>
      modeId.orElse(currentModeId).filter(_.nonEmpty)
    }.flatten

  def decodeNotify(params: Json): Option[AcpSessionNotify] =
    params.as[AcpSessionNotify].toOption

  def decodeUpdate(params: Json): Option[AcpUpdate] =
    decodeNotify(params).map(_.update).orElse(params.as[AcpUpdate].toOption)
end SessionState
