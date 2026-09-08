package groksbeard.core

import zio.test.*

object TurnActivitySpec extends ZIOSpecDefault:
  def spec =
    suite("TurnActivity")(
      test("idle model has no activity") {
        assertTrue(TurnActivity.of(ChatModel.empty, 0L).isEmpty)
      },
      test("a running turn with no tools waits for a response") {
        val model = ChatModel.empty.copy(
          turns = List(TurnView("t1", user = Some(TurnUser("hi")))),
          runningSinceMs = Some(1000),
        )
        val got = TurnActivity.of(model, 12000)
        assertTrue(
          got.map(_.kind).contains(ActivityKind.Wait),
          got.map(_.label).contains("Waiting for response..."),
          got.map(_.elapsedMs).contains(11000L),
          TurnActivity.timerLabel(11000).contains("11s"),
        )
      },
      test("thought without agent is Thinking") {
        val turn = TurnView("t1", thought = "hmm")
        val got  = TurnActivity.fromTurn(turn, 0)
        assertTrue(got.kind == ActivityKind.Think, got.detail.contains("hmm"))
      },
      test("a running execute tool watches the output tail") {
        val out  = (1 to 6).map(i => s"line-$i").mkString("\n")
        val turn = TurnView(
          "t1",
          tools = List(
            ToolRow("x1", "run_terminal_command", "execute", "in_progress", output = Some(out))
          ),
        )
        val got = TurnActivity.fromTurn(turn, 2000)
        assertTrue(
          got.kind == ActivityKind.Execute,
          got.label == "Running...",
          got.detail.contains("line-6"),
          !got.detail.exists(_.contains("line-1")),
          ToolView.liveTail(out).contains("line-4"),
          ToolView.liveTail(out).contains("line-6"),
          !ToolView.liveTail(out).contains("line-1"),
        )
      },
      test("a pending edit tool wins over thinking") {
        val turn = TurnView(
          "t1",
          thought = "I'll patch it",
          tools = List(ToolRow("e1", "Edit Main.scala", "edit", "pending")),
        )
        val got = TurnActivity.fromTurn(turn, 2000)
        assertTrue(
          got.kind == ActivityKind.Edit,
          got.label == "Editing...",
          got.path.isEmpty,
        )
      },
      test("a live read carries the file so the activity chip can open it") {
        val turn = TurnView(
          "t1",
          tools = List(
            ToolRow("r1", "Read Main.scala", "read", "in_progress", path = Some("src/Main.scala"), line = Some(12))
          ),
        )
        val got = TurnActivity.fromTurn(turn, 0)
        assertTrue(
          got.kind == ActivityKind.Read,
          got.path.contains("src/Main.scala"),
          got.line.contains(12),
        )
      },
      test("completed tools fall back to waiting") {
        val turn = TurnView(
          "t1",
          agent = "done",
          tools = List(ToolRow("r1", "Read", "read", "completed")),
        )
        assertTrue(TurnActivity.fromTurn(turn, 0).kind == ActivityKind.Wait)
      },
      test("timer hides under one second and formats minutes") {
        assertTrue(
          TurnActivity.timerLabel(0).isEmpty,
          TurnActivity.timerLabel(999).isEmpty,
          TurnActivity.timerLabel(1000).contains("1s"),
          TurnActivity.timerLabel(65000).contains("1m 5s"),
        )
      },
    )
end TurnActivitySpec
