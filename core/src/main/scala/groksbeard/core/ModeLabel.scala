package groksbeard.core

object ModeLabel:
  private val names = Map(
    ModeId.Normal        -> "Normal",
    ModeId("default")    -> "Normal",
    ModeId.Plan          -> "Plan",
    ModeId.AlwaysApprove -> "Always approve",
    ModeId("ask")        -> "Ask",
    ModeId.Auto          -> "Auto",
  )

  def titleFromId(id: ModeId): String =
    names.getOrElse(
      id,
      id.value.split("[-_]").filter(_.nonEmpty).map(p => p.take(1).toUpperCase + p.drop(1)).mkString(" "),
    )

  def modeLabel(modeId: ModeId, modes: List[ModeOption] = Nil): String =
    modes.find(_.id == modeId).filter(_.name.nonEmpty).map(_.name).getOrElse {
      titleFromId(if modeId.isEmpty then ModeId.Normal else modeId)
    }

  def nextMode(current: ModeId, modes: List[ModeOption]): ModeId =
    val available = if modes.isEmpty then List(ModeId.Normal, ModeId.Plan, ModeId.AlwaysApprove) else modes.map(_.id)
    val preferred = List(ModeId.Normal, ModeId.Plan, ModeId.Auto, ModeId.AlwaysApprove).filter(available.contains)
    val extras    = available.filterNot(preferred.contains)
    val order     = preferred ++ extras
    val idx       = order.indexOf(current)
    if order.isEmpty then ModeId.Normal
    else if idx < 0 then order.head
    else order((idx + 1) % order.size)

  def modeTip(modeId: ModeId): String =
    modeId match
      case ModeId.Normal        => "Ask before Grok runs tools"
      case ModeId.Auto          => "Approve safe tools automatically"
      case ModeId.Plan          => "Draft a plan before code edits"
      case ModeId.AlwaysApprove => "Skip permission prompts for this session"
      case _                    => s"Switch to ${titleFromId(modeId)}"
end ModeLabel
