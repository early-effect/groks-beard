package groksbeard.core

import zio.test.*

object ChatModelSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatModel")(
      test("user and agent chunks fold into one turn") {
        val model =
          ChatModel.applyMsg(ChatModel.empty, HostMsg.UserMessage("t1", "hello"))
        val streamed =
          ChatModel.applyMsg(model, HostMsg.AgentChunk("t1", "Hi "))
        val done =
          ChatModel.applyMsg(
            ChatModel.applyMsg(streamed, HostMsg.AgentChunk("t1", "there.")),
            HostMsg.TurnEnd("t1", "end_turn"),
          )
        val turn = done.turns.head
        assertTrue(
          turn.user.exists(_.text == "hello"),
          turn.agent == "Hi there.",
          turn.stopReason.contains("end_turn"),
          !ChatModel.turnIsRunning(done),
        )
      },
      test("thought chunks concatenate and tools merge by id") {
        val start = ChatModel.applyMsg(ChatModel.empty, HostMsg.ThoughtChunk("t1", "hmm"))
        val more  = ChatModel.applyMsg(start, HostMsg.ThoughtChunk("t1", " ok"))
        val tools = ChatModel.applyMsg(
          more,
          HostMsg.ToolGroup(
            "t1",
            List(
              ToolRow("a", "Read", "read", "completed", input = Some("Foo.scala")),
              ToolRow("a", "", "read", "completed", output = Some("ok")),
            ),
          ),
        )
        val row = tools.turns.head.tools.head
        assertTrue(
          tools.turns.head.thought == "hmm ok",
          row.input.contains("Foo.scala"),
          row.output.contains("ok"),
          row.title == "Read",
        )
      },
      test("turnEnd clears cards and queued") {
        val withCard = ChatModel.empty.copy(
          permission = Some(
            PermissionCard(
              "r1",
              "tc",
              "Edit Foo",
              List(PermissionOption("allow", "Allow", "allow_once")),
              hasDiff = true,
            )
          ),
          queued = 2,
        )
        val next = ChatModel.applyMsg(withCard, HostMsg.TurnEnd("t1", "end_turn"))
        assertTrue(next.permission.isEmpty, next.queued == 0, next.turns.head.stopReason.contains("end_turn"))
      },
      test("changes summary and diff preview fold into the model") {
        val withFiles = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.Changes(
            ChangesSummary(1, 2, 1, List(ChangeFileView("/tmp/Main.scala", "modify", 2, 1)))
          ),
        )
        val withDiff = ChatModel.applyMsg(
          withFiles,
          HostMsg.DiffPreview("/tmp/Main.scala", "old", "new"),
        )
        val cleared = ChatModel.applyMsg(withDiff, HostMsg.ClearDiff)
        assertTrue(
          withFiles.changes.exists(_.files.head.path == "/tmp/Main.scala"),
          withDiff.diff.exists(_.newText == "new"),
          cleared.diff.isEmpty,
          cleared.changes.nonEmpty,
        )
      },
      test("clearTranscript keeps session chrome") {
        val model = ChatModel.empty.copy(
          title = "Stay",
          commands = List(SlashCommand("compact", "Compact")),
          turns = List(TurnView("t1", agent = "gone")),
        )
        val next = ChatModel.applyMsg(model, HostMsg.ClearTranscript)
        assertTrue(next.turns.isEmpty, next.title == "Stay", next.commands.head.name == "compact")
      },
    )
end ChatModelSpec
