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
    result match
      case obj: Json.Obj =>
        obj.get("modes") match
          case Some(modes: Json.Obj) =>
            modes.get("currentModeId") match
              case Some(Json.Str(id)) if id.nonEmpty => Some(id)
              case _                                 => None
          case _ => None
      case _ => None

  def modeIdFromSessionUpdate(params: Json): Option[String] =
    val update = unwrapUpdate(params)
    update.get("sessionUpdate") match
      case Some(Json.Str("current_mode_update")) =>
        (update.get("modeId") orElse update.get("currentModeId")) match
          case Some(Json.Str(id)) if id.nonEmpty => Some(id)
          case _                                 => None
      case _ => None

  private[core] def unwrapUpdate(params: Json): Json.Obj =
    params match
      case obj: Json.Obj =>
        obj.get("update") match
          case Some(u: Json.Obj) => u
          case _                 => obj
      case _ => Json.Obj()
end SessionState
