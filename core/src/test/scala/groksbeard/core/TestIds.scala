package groksbeard.core

/** Test fixtures still write wire strings. Production APIs stay opaque / closed. */
given CanEqual[SessionId, String]        = CanEqual.canEqualAny
given CanEqual[String, SessionId]        = CanEqual.canEqualAny
given CanEqual[TurnId, String]           = CanEqual.canEqualAny
given CanEqual[String, TurnId]           = CanEqual.canEqualAny
given CanEqual[ToolCallId, String]       = CanEqual.canEqualAny
given CanEqual[String, ToolCallId]       = CanEqual.canEqualAny
given CanEqual[RequestId, String]        = CanEqual.canEqualAny
given CanEqual[String, RequestId]        = CanEqual.canEqualAny
given CanEqual[QueueId, String]          = CanEqual.canEqualAny
given CanEqual[String, QueueId]          = CanEqual.canEqualAny
given CanEqual[ModeId, String]           = CanEqual.canEqualAny
given CanEqual[String, ModeId]           = CanEqual.canEqualAny
given CanEqual[ModelId, String]          = CanEqual.canEqualAny
given CanEqual[String, ModelId]          = CanEqual.canEqualAny
given Conversion[String, SessionId]      = SessionId(_)
given Conversion[String, TurnId]         = TurnId(_)
given Conversion[String, ToolCallId]     = ToolCallId(_)
given Conversion[String, RequestId]      = RequestId(_)
given Conversion[String, QueueId]        = QueueId(_)
given Conversion[String, ModeId]         = ModeId(_)
given Conversion[String, ModelId]        = ModelId(_)
given Conversion[String, ToolKind]       = ToolKind.fromWire(_)
given Conversion[String, ToolStatus]     = ToolStatus.fromWire(_)
given Conversion[String, ChipSource]     = ChipSource.fromWire(_)
given Conversion[String, PermissionKind] = PermissionKind.fromWire(_)
given Conversion[String, StopReason]     = StopReason.fromWire(_)
given Conversion[String, ElicitMode]     = ElicitMode.fromWire(_)
given Conversion[String, TodoStatus]     = TodoStatus.fromWire(_)
given Conversion[String, TodoPriority]   = TodoPriority.fromWire(_)
given Conversion[String, ChangeKind]     = ChangeKind.fromWire(_)
given Conversion[String, PlanOutcome]    = raw =>
  PlanOutcome.fromWire(raw).getOrElse(throw IllegalArgumentException(s"unknown plan verdict: $raw"))
