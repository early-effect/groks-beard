package groksbeard.core

enum ActivityKind:
  case Wait, Think, Edit, Read, Execute, Search, Delete, Move, Other

final case class TurnActivity(kind: ActivityKind, label: String, elapsedMs: Long)

object TurnActivity:

  def of(model: ChatModel, nowMs: Long): Option[TurnActivity] =
    if !ChatModel.turnIsRunning(model) then None
    else
      val elapsed = model.runningSinceMs.map(s => math.max(0L, nowMs - s)).getOrElse(0L)
      model.turns.lastOption.map(fromTurn(_, elapsed))

  def fromTurn(turn: TurnView, elapsedMs: Long): TurnActivity =
    turn.tools.reverse.find(live).map(fromTool(_, elapsedMs)).getOrElse {
      if turn.thought.nonEmpty && turn.agent.isEmpty then TurnActivity(ActivityKind.Think, "Thinking...", elapsedMs)
      else TurnActivity(ActivityKind.Wait, "Waiting for response...", elapsedMs)
    }

  def timerLabel(ms: Long): Option[String] =
    val s = ms / 1000
    if s < 1 then None
    else if s < 60 then Some(s"${s}s")
    else if s < 3600 then Some(s"${s / 60}m ${s % 60}s")
    else Some(s"${s / 3600}h ${(s / 60) % 60}m")

  private def live(tool: ToolRow): Boolean =
    val s = tool.status.toLowerCase
    s == "pending" || s == "in_progress"

  private def fromTool(tool: ToolRow, elapsedMs: Long): TurnActivity =
    val kind = kindOf(tool.kind)
    TurnActivity(kind, labelOf(kind, tool.title), elapsedMs)

  private def kindOf(kind: String): ActivityKind =
    kind.toLowerCase match
      case "edit" | "write"                => ActivityKind.Edit
      case "read"                          => ActivityKind.Read
      case "execute" | "bash" | "terminal" => ActivityKind.Execute
      case "search" | "grep" | "glob"      => ActivityKind.Search
      case "delete"                        => ActivityKind.Delete
      case "move" | "rename"               => ActivityKind.Move
      case "think" | "thought"             => ActivityKind.Think
      case _                               => ActivityKind.Other

  private def labelOf(kind: ActivityKind, title: String): String =
    kind match
      case ActivityKind.Edit    => "Editing..."
      case ActivityKind.Read    => "Reading..."
      case ActivityKind.Execute => "Running..."
      case ActivityKind.Search  => "Searching..."
      case ActivityKind.Delete  => "Deleting..."
      case ActivityKind.Move    => "Moving..."
      case ActivityKind.Think   => "Thinking..."
      case ActivityKind.Wait    => "Waiting for response..."
      case ActivityKind.Other   =>
        val t = title.trim
        if t.nonEmpty then t else "Working..."
end TurnActivity
