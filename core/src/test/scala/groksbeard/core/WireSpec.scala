package groksbeard.core

import zio.json.*
import zio.test.*

object WireSpec extends ZIOSpecDefault:
  def spec =
    suite("Wire")(
      test("a bad host payload names the unknown _tag") {
        val msg = Wire.hostOrError("""{"_tag":"not-a-real-tag"}""")
        assertTrue(
          msg match
            case HostMsg.Error(message, Some(Wire.Decode)) => message.contains("_tag: not-a-real-tag")
            case _                                         => false
        )
      },
      test("toolGroup folds into ToolCall and ToolChunk") {
        val raw =
          """{"_tag":"toolGroup","turnId":"turn_1","tools":[{"id":"call-1","title":"run_terminal_command","kind":"execute","status":"completed","input":"echo hi","output":"hi\n"}]}"""
        val msgs = Wire.hostMsgs(raw).getOrElse(Nil)
        assertTrue(
          msgs match
            case List(HostMsg.ToolCall(turn, row), HostMsg.ToolChunk(t2, id, text, true)) =>
              turn.value == "turn_1" && row.id.value == "call-1" && row.output.isEmpty &&
              row.input.contains("echo hi") && t2.value == "turn_1" && id.value == "call-1" &&
              text == "hi\n"
            case _ => false
        )
      },
      test("a pending toolGroup is a ToolCall without a chunk") {
        val raw =
          """{"_tag":"toolGroup","turnId":"turn_1","tools":[{"id":"call-1","title":"run_terminal_command","kind":"other","status":"pending","input":"echo hi"}]}"""
        val msgs = Wire.hostMsgs(raw).getOrElse(Nil)
        assertTrue(
          msgs match
            case List(HostMsg.ToolCall(_, row)) =>
              row.title == "run_terminal_command" && row.output.isEmpty && row.input.contains("echo hi")
            case _ => false
        )
      },
      test("garbage is an Error") {
        val msg = Wire.hostOrError("not-json")
        assertTrue(
          msg match
            case HostMsg.Error(_, Some(Wire.Decode)) => true
            case _                                   => false
        )
      },
      test("a toolCall round-trips") {
        val call: HostMsg =
          HostMsg.ToolCall(
            "t1",
            ToolRow("call-92323aaa-c2e0-44e8-9bb8-84804ea1684b-0", "run_terminal_command", "execute", "pending"),
          )
        assertTrue(Wire.host(call.toJson) == Right(call))
      },
      test("a bad webview payload is Left, never skipped") {
        assertTrue(Wire.webview("nope").isLeft, Wire.webview("""{"_tag":"ready"}""") == Right(WebviewMsg.Ready))
      },
    )
end WireSpec
