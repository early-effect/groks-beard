package groksbeard.core

import zio.test.*

object ChatModelSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatModel")(
      test("sessionList opens the picker and ClearTranscript closes it") {
        val listed = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.SessionList(List(SessionRow("s1", "Plan")), currentId = "s1", openPicker = true),
        )
        val closed = ChatModel.applyMsg(listed, HostMsg.ClearTranscript)
        assertTrue(
          listed.pickerOpen,
          listed.sessions.head.title == "Plan",
          !closed.pickerOpen,
          closed.sessions.size == 1,
        )
      },
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
          HostMsg.changes(
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
      test("sessionMeta occupancy sticks across a later mode-only meta") {
        val withOcc = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.SessionMeta("s1", "Grok's Beard", "normal", occupancy = Some(Occupancy(80, 500))),
        )
        val modeOnly = ChatModel.applyMsg(withOcc, HostMsg.SessionMeta("", "", "plan"))
        assertTrue(
          withOcc.occupancy.contains(Occupancy(80, 500)),
          modeOnly.modeId == "plan",
          modeOnly.occupancy.contains(Occupancy(80, 500)),
          modeOnly.sessionId == "s1",
        )
      },
      test("composerChip upserts by path and range") {
        val first = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.chip(PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50))),
        )
        val second = ChatModel.applyMsg(
          first,
          HostMsg.chip(PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(80))),
        )
        val third = ChatModel.applyMsg(
          second,
          HostMsg.chip(PromptChip.fromFile("/repo/src/Bar.scala", Some("/repo"))),
        )
        assertTrue(
          first.chips.map(PromptChip.formatAtRef) == List("@src/Foo.scala:10-50"),
          second.chips.map(PromptChip.formatAtRef) == List("@src/Foo.scala:10-80"),
          third.chips.map(_.path) == List("src/Foo.scala", "src/Bar.scala"),
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
      test("a live grok burst keeps agent text after a fat commands dump") {
        val commands = HostMsg.AvailableCommands(
          (1 to 40).toList.map(i => SlashCommand(s"skill-$i", "hint " * 40))
        )
        val events = List(
          HostMsg.UserMessage("turn_1", "hello"),
          commands,
        ) ++
          List("The", " user", " wants", " hi").map(HostMsg.ThoughtChunk("turn_1", _)) ++
          List(
            HostMsg.AgentChunk("turn_1", "Hi"),
            HostMsg.AgentChunk("turn_1", "."),
            commands,
            HostMsg.TurnEnd("turn_1", "end_turn"),
          )
        val model = events.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val turn  = model.turns.head
        assertTrue(
          turn.user.exists(_.text == "hello"),
          turn.thought.contains("wants"),
          turn.agent == "Hi.",
          turn.stopReason.contains("end_turn"),
          model.commands.size == 40,
        )
      },
    )
end ChatModelSpec
