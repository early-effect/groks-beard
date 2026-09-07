package groksbeard.core

import zio.json.*

opaque type SessionId = String
object SessionId:
  val empty: SessionId              = ""
  def apply(raw: String): SessionId = raw.trim
  extension (id: SessionId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[SessionId] = JsonExt.stringCodec(_.value, apply)

opaque type TurnId = String
object TurnId:
  val empty: TurnId              = ""
  def apply(raw: String): TurnId = raw.trim
  def mint(seq: Int): TurnId     = apply(s"turn_$seq")
  extension (id: TurnId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[TurnId] = JsonExt.stringCodec(_.value, apply)

opaque type ToolCallId = String
object ToolCallId:
  val empty: ToolCallId              = ""
  def apply(raw: String): ToolCallId = raw.trim
  extension (id: ToolCallId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[ToolCallId] = JsonExt.stringCodec(_.value, apply)

opaque type RequestId = String
object RequestId:
  val empty: RequestId              = ""
  def apply(raw: String): RequestId = raw.trim
  extension (id: RequestId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[RequestId] = JsonExt.stringCodec(_.value, apply)

opaque type QueueId = String
object QueueId:
  def apply(raw: String): QueueId           = raw.trim
  def mint(seq: Int): QueueId               = apply(s"q$seq")
  extension (id: QueueId) def value: String = id
  given JsonCodec[QueueId]                  = JsonExt.stringCodec(_.value, apply)

opaque type ModeId = String
object ModeId:
  val empty: ModeId              = ""
  val Normal: ModeId             = "normal"
  val Plan: ModeId               = "plan"
  val Auto: ModeId               = "auto"
  val AlwaysApprove: ModeId      = "always-approve"
  def apply(raw: String): ModeId = raw.trim
  extension (id: ModeId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[ModeId] = JsonExt.stringCodec(_.value, apply)
end ModeId

opaque type TerminalId = String
object TerminalId:
  def fromWire(raw: String): Option[TerminalId] =
    val t = raw.trim
    if t.length == 0 then None else Some(t)
  def mint(seq: Int): TerminalId               = s"term-$seq"
  extension (id: TerminalId) def value: String = id
  given JsonCodec[TerminalId] = JsonExt.stringCodecOrFail(_.value, raw => fromWire(raw).toRight("empty terminalId"))

opaque type ModelId = String
object ModelId:
  val empty: ModelId              = ""
  def apply(raw: String): ModelId = raw.trim
  extension (id: ModelId)
    def value: String     = id
    def isEmpty: Boolean  = id.length == 0
    def nonEmpty: Boolean = id.length > 0
  given JsonCodec[ModelId] = JsonExt.stringCodec(_.value, apply)
