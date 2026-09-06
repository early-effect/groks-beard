package groksbeard.core

enum SessionLoadKind:
  case Locked, Failed

object SessionLoad:
  def classify(message: String): SessionLoadKind =
    val lowered = message.toLowerCase
    if lowered.contains("lock") || lowered.contains("busy") || lowered.contains("in use") then SessionLoadKind.Locked
    else SessionLoadKind.Failed

  def copy(kind: SessionLoadKind): String =
    kind match
      case SessionLoadKind.Locked => "This session is open in the TUI"
      case SessionLoadKind.Failed => "Could not resume session"
end SessionLoad
