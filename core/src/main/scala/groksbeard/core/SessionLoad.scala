package groksbeard.core

import zio.json.*
import zio.json.ast.Json

enum SessionLoadKind:
  case Locked, Failed

object SessionLoad:
  def classify(message: String, data: Option[String] = None, json: Option[Json] = None): SessionLoadKind =
    val blob    = json.map(_.toJson).getOrElse("")
    val lowered = (message + " " + data.getOrElse("") + " " + blob).toLowerCase
    if lowered.contains("lock") || lowered.contains("busy") || lowered.contains("in use") ||
      lowered.contains("already open")
    then SessionLoadKind.Locked
    else SessionLoadKind.Failed

  def copy(kind: SessionLoadKind): String =
    kind match
      case SessionLoadKind.Locked => "This session is open in the TUI"
      case SessionLoadKind.Failed => "Could not resume session"
end SessionLoad
