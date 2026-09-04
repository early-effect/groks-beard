package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object SessionUpdateSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionUpdate")(
      test("thought and agent chunks become HostMsg") {
        val thought = SessionUpdate.hostMsgs(chunk("agent_thought_chunk", "hmm"), "t1")
        val agent   = SessionUpdate.hostMsgs(chunk("agent_message_chunk", "hi"), "t1")
        assertTrue(
          thought == List(HostMsg.ThoughtChunk("t1", "hmm")),
          agent == List(HostMsg.AgentChunk("t1", "hi")),
        )
      },
      test("available_commands_update becomes commands") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "update" -> Json.Obj(
              "sessionUpdate"     -> Json.Str("available_commands_update"),
              "availableCommands" -> Json.Arr(
                Json.Obj("name" -> Json.Str("compact"), "description" -> Json.Str("Compact context"))
              ),
            )
          ),
          "t0",
        )
        assertTrue(msgs == List(HostMsg.AvailableCommands(List(SlashCommand("compact", "Compact context")))))
      },
      test("usage_update becomes occupancy on sessionMeta") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "sessionId" -> Json.Str("sess_test"),
            "update"    -> Json.Obj(
              "sessionUpdate" -> Json.Str("usage_update"),
              "used"          -> Json.Num(80),
              "size"          -> Json.Num(500),
            ),
          ),
          "t1",
        )
        assertTrue(msgs == List(HostMsg.SessionMeta("", "", "", occupancy = Some(Occupancy(80, 500)))))
      },
      test("unknown sessionUpdate is ignored") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "update" -> Json.Obj("sessionUpdate" -> Json.Str("brand_new_event"), "extra" -> Json.Bool(true))
          ),
          "t1",
        )
        assertTrue(msgs.isEmpty)
      },
    )

  private def chunk(kind: String, text: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str(kind),
        "content"       -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text)),
      ),
    )
end SessionUpdateSpec
