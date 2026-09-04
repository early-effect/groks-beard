package groksbeard.core

object ModeLabel:
  private val names = Map(
    "normal"         -> "Normal",
    "plan"           -> "Plan",
    "always-approve" -> "Always approve",
    "ask"            -> "Ask",
    "auto"           -> "Auto",
  )

  def titleFromId(id: String): String =
    names.getOrElse(
      id,
      id.split("[-_]").filter(_.nonEmpty).map(p => p.take(1).toUpperCase + p.drop(1)).mkString(" "),
    )

  def modeLabel(modeId: String, modes: List[ModeOption] = Nil): String =
    modes.find(_.id == modeId).filter(_.name.nonEmpty).map(_.name).getOrElse {
      titleFromId(if modeId.isEmpty then "normal" else modeId)
    }

  def nextMode(current: String, modes: List[ModeOption]): String =
    val available = if modes.isEmpty then List("normal", "plan", "always-approve") else modes.map(_.id)
    val preferred = List("normal", "plan", "auto", "always-approve").filter(available.contains)
    val extras    = available.filterNot(preferred.contains)
    val order     = preferred ++ extras
    val idx       = order.indexOf(current)
    if order.isEmpty then "normal"
    else if idx < 0 then order.head
    else order((idx + 1) % order.size)

  def modeTip(modeId: String): String =
    modeId match
      case "normal"         => "Ask before Grok runs tools"
      case "auto"           => "Approve safe tools automatically"
      case "plan"           => "Draft a plan before code edits"
      case "always-approve" => "Skip permission prompts for this session"
      case _                => s"Switch to ${titleFromId(modeId)}"
end ModeLabel
