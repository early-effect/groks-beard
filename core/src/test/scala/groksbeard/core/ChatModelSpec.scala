package groksbeard.core

import java.util.concurrent.TimeUnit
import zio.*
import zio.test.*

object ChatModelSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatModel")(
      test("ClearCard dismisses plan without ending the turn") {
        val open = ChatModel.applyMsg(ChatModel.empty, HostMsg.Plan("p1", "# Plan"))
        val gone = ChatModel.applyMsg(open, HostMsg.ClearCard(CardSlot.Plan))
        assertTrue(open.plan.isDefined, gone.plan.isEmpty)
      },
      test("sessionMeta can populate models after the session is already open") {
        val later = ChatModel.applyMsg(
          ChatModel.empty.copy(inSession = true, sessionId = "s1"),
          HostMsg.SessionMeta(
            "s1",
            "Plan",
            ModeId.Normal,
            modelId = "grok-4.6",
            availableModels = List(ModelOption(ModelId("grok-4.6"), "Grok 4.6")),
            effort = "high",
          ),
        )
        assertTrue(
          later.modelId == "grok-4.6",
          later.models.head.name == "Grok 4.6",
          later.effort == "high",
        )
      },
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
          turn.stopReason.contains(StopReason.EndTurn),
          !ChatModel.turnIsRunning(done),
          model.runningSinceMs.contains(now),
          done.runningSinceMs.isEmpty,
        )
      } @@ TestAspect.withLiveClock,
      test("a finished execute keeps command and stdout after a sparse completed update") {
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stream  = "beard-terminal-probe\n/tmp"
        val stdout  = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        val live    = ChatModel.applyMsg(
          ChatModel.applyMsg(
            ChatModel.empty,
            HostMsg.ToolCall(
              "t1",
              ToolRow(
                "term-1",
                "run_terminal_command",
                "execute",
                "in_progress",
                input = Some(command),
              ),
            ),
          ),
          HostMsg.ToolChunk("t1", "term-1", stream, snapshot = true),
        )
        val done = ChatModel.applyMsg(
          ChatModel.applyMsg(
            live,
            HostMsg.ToolCall("t1", ToolRow("term-1", "Tool", "other", "completed")),
          ),
          HostMsg.ToolChunk("t1", "term-1", stdout, snapshot = true),
        )
        val row = done.turns.head.tools.head
        assertTrue(
          live.turns.head.tools.head.status == ToolStatus.InProgress,
          row.title == "run_terminal_command",
          row.kind == ToolKind.Execute,
          row.status == ToolStatus.Completed,
          row.input.contains(command),
          row.output.contains(stdout),
          ToolView.liveTail(stream).contains("/tmp"),
        )
      },
      test("Rewound truncates turns through the chosen prompt") {
        val model = ChatModel.empty.copy(
          turns = List(
            TurnView("t1", user = Some(TurnUser("first")), agent = "a", stopReason = Some(StopReason.EndTurn)),
            TurnView("t2", user = Some(TurnUser("second")), agent = "b", stopReason = Some(StopReason.EndTurn)),
          ),
          rewind = List(RewindPoint(0, "first"), RewindPoint(1, "second")),
          rewindConfirm = Some(RewindPoint(0, "first")),
        )
        val next = ChatModel.applyMsg(model, HostMsg.Rewound(0))
        assertTrue(
          next.turns.map(_.id.value) == List("t1"),
          next.rewind.isEmpty,
          next.rewindConfirm.isEmpty,
        )
      },
      test("RewindList keeps confirm when the point is still present") {
        val armed = RewindPoint(0, "first")
        val model = ChatModel.empty.copy(rewind = List(armed), rewindConfirm = Some(armed))
        val keep  = ChatModel.applyMsg(model, HostMsg.RewindList(List(RewindPoint(0, "first prompt"))))
        val drop  = ChatModel.applyMsg(model, HostMsg.RewindList(List(RewindPoint(1, "later"))))
        assertTrue(
          keep.rewindConfirm.exists(_.preview == "first prompt"),
          drop.rewindConfirm.isEmpty,
        )
      },
      test("tool chunks append deltas and grow snapshots") {
        val start = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.ToolCall(
            "t1",
            ToolRow("term-1", "run_terminal_command", "execute", "in_progress"),
          ),
        )
        val d1   = ChatModel.applyMsg(start, HostMsg.ToolChunk("t1", "term-1", "line-1\n"))
        val d2   = ChatModel.applyMsg(d1, HostMsg.ToolChunk("t1", "term-1", "line-2\n"))
        val snap =
          ChatModel.applyMsg(d2, HostMsg.ToolChunk("t1", "term-1", "line-1\nline-2\nline-3\n", snapshot = true))
        assertTrue(
          d1.turns.head.tools.head.output.contains("line-1\n"),
          d2.turns.head.tools.head.output.contains("line-1\nline-2\n"),
          snap.turns.head.tools.head.output.contains("line-1\nline-2\nline-3\n"),
        )
      },
      test("thought chunks concatenate and tools merge by id") {
        val start = ChatModel.applyMsg(ChatModel.empty, HostMsg.ThoughtChunk("t1", "hmm"))
        val more  = ChatModel.applyMsg(start, HostMsg.ThoughtChunk("t1", " ok"))
        val tools = List(
          HostMsg.ToolCall("t1", ToolRow("a", "Read", "read", "completed", input = Some("Foo.scala"))),
          HostMsg.ToolCall("t1", ToolRow("a", "", "read", "completed")),
          HostMsg.ToolChunk("t1", "a", "ok"),
        ).foldLeft(more)(ChatModel.applyMsg)
        val row = tools.turns.head.tools.head
        assertTrue(
          tools.turns.head.thought == "hmm ok",
          row.input.contains("Foo.scala"),
          row.output.contains("ok"),
          row.title == "Read",
        )
      },
      test("tool locations merge onto the row and survive a title-only update") {
        val start = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.ToolCall(
            "t1",
            ToolRow("a", "Read", "read", "in_progress", path = Some("src/Foo.scala"), line = Some(3)),
          ),
        )
        val moved = ChatModel.applyMsg(
          start,
          HostMsg.ToolCall(
            "t1",
            ToolRow("a", "Read", "read", "in_progress", path = Some("src/Foo.scala"), line = Some(9)),
          ),
        )
        val keep = ChatModel.applyMsg(
          moved,
          HostMsg.ToolCall("t1", ToolRow("a", "", "read", "completed")),
        )
        val row = keep.turns.head.tools.head
        assertTrue(
          moved.turns.head.tools.head.line.contains(9),
          row.path.contains("src/Foo.scala"),
          row.line.contains(9),
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
          next.turns.head.stopReason.contains(StopReason.EndTurn),
        )
      },
      test("copied toast uses the status slot") {
        val next = ChatModel.applyMsg(ChatModel.empty, HostMsg.Copied("Copied!"))
        assertTrue(next.error.contains("Copied!"))
      },
      test("todos replace the list and survive a turn end") {
        val first = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.Todos(List(TodoEntry("One", "pending"), TodoEntry("Two", "in_progress"))),
        )
        val next = ChatModel.applyMsg(
          first,
          HostMsg.Todos(List(TodoEntry("One", "completed"), TodoEntry("Two", "in_progress"))),
        )
        val ended = ChatModel.applyMsg(next, HostMsg.TurnEnd("t1", "end_turn"))
        val gone  = ChatModel.applyMsg(ended, HostMsg.ClearTranscript)
        val home  = ChatModel.adopt(next, "", "Grok's Beard")
        assertTrue(
          first.todos.map(_.status) == List(Todos.Pending, Todos.InProgress),
          next.todos.map(_.status) == List(Todos.Completed, Todos.InProgress),
          ended.todos.size == 2,
          gone.todos.isEmpty,
          home.todos.isEmpty,
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
          withOcc.inSession,
          ChatModel.isLoading(withOcc),
          !ChatModel.isHome(withOcc),
          modeOnly.modeId == "plan",
          modeOnly.occupancy.contains(Occupancy(80, 500)),
          modeOnly.sessionId == "s1",
        )
      },
      test("sessionMeta cwd sticks across a later mode-only meta") {
        val withCwd =
          ChatModel.applyMsg(ChatModel.empty, HostMsg.SessionMeta("s1", "Grok's Beard", "normal", cwd = "/repo"))
        val modeOnly = ChatModel.applyMsg(withCwd, HostMsg.SessionMeta("", "", "plan"))
        assertTrue(withCwd.cwd == "/repo", modeOnly.cwd == "/repo", modeOnly.modeId == "plan")
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
      test("sessionMeta effort sticks across a later mode-only meta and clears on a model switch") {
        val withEffort = ChatModel.applyMsg(
          ChatModel.empty,
          HostMsg.SessionMeta(
            "s1",
            "Grok's Beard",
            "normal",
            modelId = "grok-4.6",
            availableModels = List(ModelOption("grok-4.6", "Grok 4.6")),
            effort = "high",
          ),
        )
        val modeOnly = ChatModel.applyMsg(withEffort, HostMsg.SessionMeta("", "", "plan"))
        val switched = ChatModel.applyMsg(
          modeOnly,
          HostMsg.SessionMeta("", "", "", modelId = "grok-code-fast-1", effort = ""),
        )
        assertTrue(
          withEffort.effort == "high",
          modeOnly.effort == "high",
          modeOnly.modeId == "plan",
          switched.modelId == "grok-code-fast-1",
          switched.effort.isEmpty,
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
      test("a live chunk reopens an ended turn") {
        val ended = ChatModel.applyMsg(
          ChatModel.applyMsg(ChatModel.empty, HostMsg.UserMessage("t1", "go")),
          HostMsg.TurnEnd("t1", StopReason.EndTurn),
        )
        val live = ChatModel.applyMsg(
          ended,
          HostMsg.ToolCall("t1", ToolRow("c1", "run", ToolKind.Execute, ToolStatus.InProgress)),
        )
        val done = ChatModel.applyMsg(live, HostMsg.TurnEnd("t1", StopReason.EndTurn))
        assertTrue(
          !ChatModel.turnIsRunning(ended),
          ChatModel.turnIsRunning(live),
          live.turns.last.stopReason.isEmpty,
          !ChatModel.turnIsRunning(done),
        )
      },
      test("snapshotTurns keeps a live last turn running") {
        val raw = List(
          TurnView("t0", user = Some(TurnUser("old")), agent = "done"),
          TurnView(
            "t1",
            user = Some(TurnUser("go")),
            thought = "working",
            tools = List(ToolRow("c1", "run", ToolKind.Execute, ToolStatus.InProgress)),
          ),
        )
        val snap = ChatModel.snapshotTurns(raw)
        assertTrue(
          snap.head.stopReason.contains(StopReason.EndTurn),
          snap.last.stopReason.isEmpty,
          ChatModel.stillLive(snap.last),
          snap.last.thought == "working",
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
          snap.head.stopReason.contains(StopReason.EndTurn),
          snap.head.tools.head.input.isEmpty,
          snap.head.tools.head.output.isEmpty,
          snap.head.agent == "hello",
        )
      },
      test("Tasks pins a subagent onto the last turn and updates in place") {
        val user   = ChatModel.applyMsg(ChatModel.empty, HostMsg.UserMessage("t1", "research"))
        val live   = TaskRow("s1", TaskKind.Subagent, TaskStatus.Running, "do the thing", "explore · grok-4.6")
        val pinned = ChatModel.applyMsg(user, HostMsg.Tasks(List(live)))
        val done   = ChatModel.applyMsg(pinned, HostMsg.Tasks(List(live.copy(status = TaskStatus.Completed))))
        val notice = ChatModel.applyMsg(done, HostMsg.TaskNotice("Task completed · sbt compile"))
        assertTrue(
          pinned.turns.size == 1,
          pinned.turns.head.subagents.headOption.exists(r => r.id.value == "s1" && r.status == TaskStatus.Running),
          done.turns.size == 1,
          done.turns.head.subagents.headOption.exists(_.status == TaskStatus.Completed),
          notice.turns.size == 2,
          notice.turns.last.agent == "Task completed · sbt compile",
        )
      },
      test("snapshotTurns keeps pinned subagents") {
        val raw = List(
          TurnView(
            "t1",
            user = Some(TurnUser("hi")),
            thought = "secret",
            agent = "hello",
            subagents = List(TaskRow("s1", TaskKind.Subagent, TaskStatus.Completed, "do the thing")),
          )
        )
        val snap = ChatModel.snapshotTurns(raw)
        assertTrue(
          snap.head.thought.isEmpty,
          snap.head.subagents.headOption.exists(r => r.id.value == "s1" && r.label == "do the thing"),
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
      test("MCP auth notice and elicit stay visible while a session is loading") {
        val awaiting = ChatModel.adopt(ChatModel.empty, "b", "B")
        val notice   = ChatModel.applyMsg(awaiting, HostMsg.Error("atlassian MCP needs authentication."))
        val elicit   = ChatModel.applyMsg(
          notice,
          HostMsg.elicit(ElicitCard(RequestId("el-1"), "atlassian", ElicitMode.Url, "Sign in", None)),
        )
        assertTrue(
          ChatModel.isLoading(notice),
          notice.error.exists(_.contains("atlassian")),
          ChatModel.isLoading(elicit),
          elicit.elicit.exists(_.serverName == "atlassian"),
          elicit.awaitingSession.contains("b"),
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
          turn.stopReason.contains(StopReason.EndTurn),
          model.commands.size == 40,
        )
      },
    )
end ChatModelSpec
