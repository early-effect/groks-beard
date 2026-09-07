package groksbeard.core

import ascent.squawk.Eq
import zio.json.*

enum TodoStatus:
  case Pending, InProgress, Completed

object TodoStatus:
  def wire(status: TodoStatus): String =
    status match
      case TodoStatus.Pending    => "pending"
      case TodoStatus.InProgress => "in_progress"
      case TodoStatus.Completed  => "completed"

  def fromWire(raw: String): TodoStatus =
    raw.trim.toLowerCase.replace('-', '_').replace(' ', '_') match
      case "completed" | "complete" | "done"                 => TodoStatus.Completed
      case "in_progress" | "inprogress" | "active" | "doing" => TodoStatus.InProgress
      case _                                                 => TodoStatus.Pending

  given JsonCodec[TodoStatus] = JsonExt.stringCodec(wire, fromWire)
  given Eq[TodoStatus]        = (a, b) => a == b
end TodoStatus

enum TodoPriority:
  case High, Medium, Low

object TodoPriority:
  def wire(priority: TodoPriority): String =
    priority match
      case TodoPriority.High   => "high"
      case TodoPriority.Medium => "medium"
      case TodoPriority.Low    => "low"

  def fromWire(raw: String): TodoPriority =
    raw.trim.toLowerCase match
      case "high" => TodoPriority.High
      case "low"  => TodoPriority.Low
      case _      => TodoPriority.Medium

  given JsonCodec[TodoPriority] = JsonExt.stringCodec(wire, fromWire)
  given Eq[TodoPriority]        = (a, b) => a == b
end TodoPriority

enum PlanOutcome:
  case Approved, Cancelled, Abandoned

object PlanOutcome:
  def wire(outcome: PlanOutcome): String =
    outcome match
      case PlanOutcome.Approved  => "approved"
      case PlanOutcome.Cancelled => "cancelled"
      case PlanOutcome.Abandoned => "abandoned"

  def fromWire(raw: String): Option[PlanOutcome] =
    raw.trim.toLowerCase match
      case "approved"  => Some(PlanOutcome.Approved)
      case "cancelled" => Some(PlanOutcome.Cancelled)
      case "abandoned" => Some(PlanOutcome.Abandoned)
      case _           => None

  given JsonCodec[PlanOutcome] = JsonExt.stringCodecOrFail(
    wire,
    raw => fromWire(raw).toRight(s"unknown plan verdict: $raw"),
  )
end PlanOutcome

enum ChipSource:
  case Selection, File, Active, Mention

object ChipSource:
  def wire(source: ChipSource): String =
    source match
      case ChipSource.Selection => "selection"
      case ChipSource.File      => "file"
      case ChipSource.Active    => "active"
      case ChipSource.Mention   => "mention"

  def fromWire(raw: String): ChipSource =
    raw.trim.toLowerCase match
      case "selection" => ChipSource.Selection
      case "active"    => ChipSource.Active
      case "mention"   => ChipSource.Mention
      case _           => ChipSource.File

  given JsonCodec[ChipSource] = JsonExt.stringCodec(wire, fromWire)
  given Eq[ChipSource]        = (a, b) => a == b
end ChipSource

enum ToolStatus:
  case Pending, InProgress, Completed, Failed

object ToolStatus:
  def wire(status: ToolStatus): String =
    status match
      case ToolStatus.Pending    => "pending"
      case ToolStatus.InProgress => "in_progress"
      case ToolStatus.Completed  => "completed"
      case ToolStatus.Failed     => "failed"

  def fromWire(raw: String): ToolStatus =
    raw.trim.toLowerCase.replace('-', '_') match
      case "completed" | "complete"     => ToolStatus.Completed
      case "in_progress" | "inprogress" => ToolStatus.InProgress
      case "failed" | "error"           => ToolStatus.Failed
      case _                            => ToolStatus.Pending

  def isLive(status: ToolStatus): Boolean =
    status == ToolStatus.Pending || status == ToolStatus.InProgress

  given JsonCodec[ToolStatus] = JsonExt.stringCodec(wire, fromWire)
  given Eq[ToolStatus]        = (a, b) => a == b
end ToolStatus

enum ToolKind:
  case Read, Edit, Execute, Search, Delete, Move, Think, Other

object ToolKind:
  def wire(kind: ToolKind): String =
    kind match
      case ToolKind.Read    => "read"
      case ToolKind.Edit    => "edit"
      case ToolKind.Execute => "execute"
      case ToolKind.Search  => "search"
      case ToolKind.Delete  => "delete"
      case ToolKind.Move    => "move"
      case ToolKind.Think   => "think"
      case ToolKind.Other   => "other"

  def fromWire(raw: String): ToolKind =
    raw.trim.toLowerCase match
      case "edit" | "write"                => ToolKind.Edit
      case "read"                          => ToolKind.Read
      case "execute" | "bash" | "terminal" => ToolKind.Execute
      case "search" | "grep" | "glob"      => ToolKind.Search
      case "delete"                        => ToolKind.Delete
      case "move" | "rename"               => ToolKind.Move
      case "think" | "thought"             => ToolKind.Think
      case _                               => ToolKind.Other

  given JsonCodec[ToolKind] = JsonExt.stringCodec(wire, fromWire)
  given Eq[ToolKind]        = (a, b) => a == b
end ToolKind

enum StopReason:
  case EndTurn, Cancelled, MaxTokens, MaxTurnRequests, Refusal, Unknown

object StopReason:
  def wire(reason: StopReason): String =
    reason match
      case StopReason.EndTurn         => "end_turn"
      case StopReason.Cancelled       => "cancelled"
      case StopReason.MaxTokens       => "max_tokens"
      case StopReason.MaxTurnRequests => "max_turn_requests"
      case StopReason.Refusal         => "refusal"
      case StopReason.Unknown         => "unknown"

  def fromWire(raw: String): StopReason =
    raw.trim.toLowerCase match
      case "end_turn"          => StopReason.EndTurn
      case "cancelled"         => StopReason.Cancelled
      case "max_tokens"        => StopReason.MaxTokens
      case "max_turn_requests" => StopReason.MaxTurnRequests
      case "refusal"           => StopReason.Refusal
      case _                   => StopReason.Unknown

  given JsonCodec[StopReason] = JsonExt.stringCodec(wire, fromWire)
  given Eq[StopReason]        = (a, b) => a == b
end StopReason

enum PermissionKind:
  case AllowOnce, AllowAlways, RejectOnce, RejectAlways, Other

object PermissionKind:
  def wire(kind: PermissionKind): String =
    kind match
      case PermissionKind.AllowOnce    => "allow_once"
      case PermissionKind.AllowAlways  => "allow_always"
      case PermissionKind.RejectOnce   => "reject_once"
      case PermissionKind.RejectAlways => "reject_always"
      case PermissionKind.Other        => "other"

  def fromWire(raw: String): PermissionKind =
    raw.trim.toLowerCase.replace('-', '_') match
      case "allow_once"    => PermissionKind.AllowOnce
      case "allow_always"  => PermissionKind.AllowAlways
      case "reject_once"   => PermissionKind.RejectOnce
      case "reject_always" => PermissionKind.RejectAlways
      case _               => PermissionKind.Other

  given JsonCodec[PermissionKind] = JsonExt.stringCodec(wire, fromWire)
  given Eq[PermissionKind]        = (a, b) => a == b
end PermissionKind

enum ElicitMode:
  case Url, Form

object ElicitMode:
  def wire(mode: ElicitMode): String =
    mode match
      case ElicitMode.Url  => "url"
      case ElicitMode.Form => "form"

  def fromWire(raw: String): ElicitMode =
    if raw.trim.equalsIgnoreCase("url") then ElicitMode.Url else ElicitMode.Form

  given JsonCodec[ElicitMode] = JsonExt.stringCodec(wire, fromWire)
  given Eq[ElicitMode]        = (a, b) => a == b
end ElicitMode
