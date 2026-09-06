package groksbeard.core

import java.util.concurrent.TimeUnit
import zio.*
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
        for
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          model    = ChatModel.applyMsg(ChatModel.empty, HostMsg.UserMessage("t1", "hello"), now)
          streamed = ChatModel.applyMsg(model, HostMsg.AgentChunk("t1", "Hi "), now)
          done     = ChatModel.applyMsg(
            ChatModel.applyMsg(streamed, HostMsg.AgentChunk("t1", "there."), now),
            HostMsg.TurnEnd("t1", "end_turn"),
            now,
          )
          turn = done.turns.head
        yield assertTrue(
          turn.user.exists(_.text == "hello"),
          turn.agent == "Hi there.",
          turn.stopReason.contains("end_turn"),
          !ChatModel.turnIsRunning(done),
          model.runningSinceMs.contains(now),
          done.runningSinceMs.isEmpty,
        )
      } @@ TestAspect.withLiveClock,
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
      test("turnEnd clears cards and leaves parked follow-ups") {
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
          queue = List(QueuedPrompt("q1", "later")),
        )
        val next = ChatModel.applyMsg(withCard, HostMsg.TurnEnd("t1", "end_turn"))
        assertTrue(
          next.permission.isEmpty,
          next.queue.map(_.text) == List("later"),
          next.turns.head.stopReason.contains("end_turn"),
        )
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
      test("sessionMeta models stick across a later mode-only meta") {
        val withModels = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.SessionMeta(
            "s1",
            "Grok's Beard",
            "normal",
            modelId = "grok-4.6",
            availableModels = List(ModelOption("grok-4.6", "Grok 4.6")),
          ),
        )
        val modeOnly = ChatModel.applyMsg(withModels, HostMsg.SessionMeta("", "", "plan"))
        assertTrue(
          withModels.modelId == "grok-4.6",
          withModels.models.exists(_.modelId == "grok-4.6"),
          modeOnly.modeId == "plan",
          modeOnly.modelId == "grok-4.6",
          modeOnly.models.exists(_.modelId == "grok-4.6"),
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
      test("transcript snapshot replaces turns in one message") {
        val snap = HostMsg.Transcript(
          List(TurnView("t1", user = Some(TurnUser("hello from disk")), agent = "welcome back"))
        )
        val next = ChatModel.applyMsg(ChatModel.empty.copy(turns = List(TurnView("old"))), snap)
        assertTrue(
          next.turns.size == 1,
          next.turns.head.user.exists(_.text == "hello from disk"),
          next.turns.head.agent == "welcome back",
          next.awaitingSession.isEmpty,
        )
      },
      test("snapshotTurns drops thoughts and tool bodies") {
        val raw = List(
          TurnView(
            "t1",
            user = Some(TurnUser("hi")),
            thought = "secret",
            agent = "hello",
            tools = List(ToolRow("c1", "Edit", "edit", "completed", input = Some("path"), output = Some("out"))),
          )
        )
        val snap = ChatModel.snapshotTurns(raw)
        assertTrue(
          snap.head.thought.isEmpty,
          snap.head.stopReason.contains("end_turn"),
          snap.head.tools.head.input.isEmpty,
          snap.head.tools.head.output.isEmpty,
          snap.head.agent == "hello",
        )
      },
      test("opening a session freezes list order across a last-accessed bump") {
        val home = ChatModel.empty.copy(
          sessions = List(
            SessionRow("a", "A", activityMs = 9),
            SessionRow("b", "B", activityMs = 1),
          )
        )
        val opened = ChatModel.adopt(home, "b", "B")
        val bumped = ChatModel.applyMsg(
          opened,
          HostMsg.SessionList(
            List(SessionRow("b", "B", activityMs = 100), SessionRow("a", "A", activityMs = 9)),
            currentId = "b",
            openPicker = false,
          ),
        )
        val picker = ChatModel.applyMsg(
          bumped,
          HostMsg.SessionList(
            List(SessionRow("b", "B", activityMs = 100), SessionRow("a", "A", activityMs = 9)),
            currentId = "b",
            openPicker = true,
          ),
        )
        assertTrue(
          ChatModel.listed(home).map(_.id) == List("a", "b"),
          ChatModel.listed(opened).map(_.id) == List("a", "b"),
          ChatModel.listed(bumped).map(_.id) == List("a", "b"),
          ChatModel.listed(picker).map(_.id) == List("b", "a"),
          picker.sessionOrder.isEmpty,
        )
      },
      test("adopt of a session id leaves home before any host message") {
        val home = ChatModel.empty.copy(sessions = List(SessionRow("b", "B")))
        val next = ChatModel.adopt(home, "b", "B")
        val meta = ChatModel.applyMsg(next, HostMsg.SessionMeta("b", "B", "normal"))
        val list = ChatModel.applyMsg(
          meta,
          HostMsg.SessionList(home.sessions, currentId = "b", openPicker = false),
        )
        assertTrue(
          next.inSession,
          ChatModel.isLoading(next),
          !ChatModel.isHome(next),
          next.sessionId == "b",
          next.title == "B",
          ChatModel.isLoading(meta),
          ChatModel.isLoading(list),
          !ChatModel.isHome(list),
        )
      },
      test("an empty transcript stays in the session, not home") {
        val awaiting = ChatModel.adopt(ChatModel.empty, "b", "B")
        val snap     = ChatModel.applyMsg(awaiting, HostMsg.Transcript(Nil))
        assertTrue(
          snap.inSession,
          snap.awaitingSession.isEmpty,
          !ChatModel.isLoading(snap),
          ChatModel.isEmptySession(snap),
          !ChatModel.isHome(snap),
        )
      },
      test("adopt of an empty id returns to home") {
        val open = ChatModel.adopt(ChatModel.empty, "b", "B")
        val home = ChatModel.adopt(open, "", "Grok's Beard")
        assertTrue(
          !home.inSession,
          home.sessionId.isEmpty,
          ChatModel.isHome(home),
          !ChatModel.isLoading(home),
        )
      },
      test("awaiting a session drops the previous transcript chunks") {
        val awaiting = ChatModel.adopt(ChatModel.empty.copy(sessionId = "a", title = "A"), "b", "B")
        val stale    = ChatModel.applyMsg(awaiting, HostMsg.UserMessage("t1", "from A"))
        val meta     = ChatModel.applyMsg(stale, HostMsg.SessionMeta("b", "B", "normal"))
        val live     = ChatModel.applyMsg(meta, HostMsg.UserMessage("t2", "from B"))
        val snap     = ChatModel.applyMsg(
          live,
          HostMsg.Transcript(List(TurnView("t2", user = Some(TurnUser("from B"))))),
        )
        assertTrue(
          awaiting.awaitingSession.contains("b"),
          ChatModel.isLoading(awaiting),
          stale.turns.isEmpty,
          meta.awaitingSession.contains("b"),
          meta.title == "B",
          live.turns.isEmpty,
          snap.awaitingSession.isEmpty,
          snap.inSession,
          snap.turns.exists(_.user.exists(_.text == "from B")),
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
