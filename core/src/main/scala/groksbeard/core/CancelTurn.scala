package groksbeard.core

enum CancelChoice:
  case StopRunning, ContinueToRun, AlwaysStop, AlwaysContinue

object CancelTurn:
  val EscHint: String   = "Press Ctrl+C to cancel the turn"
  val ClearHint: String = "press again to clear"
  val Title: String     = "Stop them?"

  def liveSubagents(rows: List[TaskRow]): List[TaskRow] =
    rows.filter(r => r.kind == TaskKind.Subagent && TaskStatus.isLive(r.status))

  def needsPanel(rows: List[TaskRow], remembered: Option[Boolean]): Boolean =
    remembered.isEmpty && liveSubagents(rows).nonEmpty

  def heading(live: Int): String =
    if live == 1 then "1 subagent running"
    else s"$live subagents running"

  def rows: List[(Int, CancelChoice, String)] =
    List(
      (1, CancelChoice.StopRunning, "Stop running"),
      (2, CancelChoice.ContinueToRun, "Continue to run"),
      (3, CancelChoice.AlwaysStop, "Always stop"),
      (4, CancelChoice.AlwaysContinue, "Always continue"),
    )

  def pick(key: String): Option[CancelChoice] =
    key match
      case "1" => Some(CancelChoice.StopRunning)
      case "2" => Some(CancelChoice.ContinueToRun)
      case "3" => Some(CancelChoice.AlwaysStop)
      case "4" => Some(CancelChoice.AlwaysContinue)
      case _   => None

  def keepChildren(choice: CancelChoice): Boolean =
    choice == CancelChoice.ContinueToRun || choice == CancelChoice.AlwaysContinue

  def remember(choice: CancelChoice): Option[Boolean] =
    choice match
      case CancelChoice.AlwaysStop     => Some(false)
      case CancelChoice.AlwaysContinue => Some(true)
      case _                           => None
end CancelTurn
