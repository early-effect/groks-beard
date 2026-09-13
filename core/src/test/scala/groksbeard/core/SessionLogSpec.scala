package groksbeard.core

import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Gen

object SessionLogSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionLog")(
      test("folds user, agent, tool identity, plan, and turn_completed") {
        val lines = List(
          disk(AcpUpdate.User(AcpContent.Text("hello from disk"))),
          disk(
            AcpUpdate.ToolCall(
              toolCallId = ToolCallId("c1"),
              title = "Read Main.scala",
              kind = ToolKind.Read,
              status = ToolStatus.Pending,
            )
          ),
          disk(
            AcpUpdate.ToolCallUpdate(
              toolCallId = ToolCallId("c1"),
              title = "Read Main.scala",
              kind = ToolKind.Read,
              status = ToolStatus.Completed,
            )
          ),
          disk(AcpUpdate.Agent(AcpContent.Text("welcome back"))),
          disk(
            AcpUpdate.Plan(
              List(
                TodoEntry("Replay the disk snapshot", Todos.Completed, TodoPriority.Medium),
                TodoEntry("Continue the work", Todos.InProgress, TodoPriority.High),
              )
            )
          ),
          disk(AcpUpdate.TurnCompleted(StopReason.EndTurn), method = "_x.ai/session/update"),
        )
        val snap = SessionLog.fold(lines)
        val turn = snap.turns.head
        assertTrue(
          snap.turns.size == 1,
          turn.user.exists(_.text == "hello from disk"),
          turn.agent.contains("welcome back"),
          turn.thought.isEmpty,
          turn.stopReason.contains(StopReason.EndTurn),
          turn.tools.head.id == ToolCallId("c1"),
          turn.tools.head.title == "Read Main.scala",
          turn.tools.head.status == ToolStatus.Completed,
          turn.tools.head.input.isEmpty,
          turn.tools.head.output.isEmpty,
          snap.todos.map(_.content) == List("Replay the disk snapshot", "Continue the work"),
          snap.todos.map(_.status) == List(Todos.Completed, Todos.InProgress),
        )
      },
      test("drops thought chunks") {
        val snap = SessionLog.fold(
          List(
            disk(AcpUpdate.User(AcpContent.Text("ask"))),
            disk(AcpUpdate.Thought(AcpContent.Text("secret chain of thought"))),
            disk(AcpUpdate.Agent(AcpContent.Text("answer"))),
          )
        )
        assertTrue(snap.turns.head.thought.isEmpty, snap.turns.head.agent.contains("answer"))
      },
      test("scans tool_call_update lines instead of parsing bodies") {
        val body = "secret-tool-body"
        val lines = List(
          disk(
            AcpUpdate.ToolCall(
              toolCallId = ToolCallId("c1"),
              title = "Edit",
              kind = ToolKind.Edit,
              status = ToolStatus.Pending,
            )
          ),
          s"""{"method":"session/update","params":{"sessionId":"sess","update":{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","title":"Edit","content":[{"type":"content","content":"$body"}]}}}""",
        )
        val snap = SessionLog.fold(lines)
        val row  = snap.turns.head.tools.head
        assertTrue(
          row.id == ToolCallId("c1"),
          row.status == ToolStatus.Completed,
          row.title == "Edit",
          row.output.isEmpty,
          !snap.turns.exists(_.agent.contains(body)),
        )
      },
      test("scans a huge tool_call_update for status without keeping the body") {
        val fat = "x" * (SessionLog.HeavyBytes + 100)
        val lines = List(
          disk(
            AcpUpdate.ToolCall(
              toolCallId = ToolCallId("c1"),
              title = "Edit",
              kind = ToolKind.Edit,
              status = ToolStatus.Pending,
            )
          ),
          s"""{"method":"session/update","params":{"sessionId":"sess","update":{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","title":"Edit","content":[{"type":"content","content":"$fat"}]}}}""",
        )
        val snap = SessionLog.fold(lines)
        val row  = snap.turns.head.tools.head
        assertTrue(
          row.id == ToolCallId("c1"),
          row.status == ToolStatus.Completed,
          row.title == "Edit",
          row.output.isEmpty,
          row.input.isEmpty,
          !snap.turns.exists(_.agent.contains("x")),
        )
      },
      test("skips empty and unknown lines") {
        val snap = SessionLog.fold(List("", "{}", """{"method":"ping"}"""))
        assertTrue(snap.turns.isEmpty, snap.todos.isEmpty)
      },
      test("concatenating line batches equals folding them in order") {
        check(SessionLogSpec.genLines, SessionLogSpec.genLines) { (a, b) =>
          val one = SessionLog.fold(a ++ b)
          val two = SessionLog.finish(b.foldLeft(a.foldLeft(SessionLog.empty)(SessionLog.foldLine))(SessionLog.foldLine))
          assertTrue(one == two)
        }
      },
      test("thoughts and tool bodies never appear on the finished snapshot") {
        check(SessionLogSpec.genLines) { lines =>
          val snap = SessionLog.fold(lines)
          assertTrue(
            snap.turns.forall(_.thought.isEmpty),
            snap.turns.forall(_.tools.forall(t => t.input.isEmpty && t.output.isEmpty)),
          )
        }
      },
      test("tool identity survives a body-bearing update") {
        val id = ToolCallId("c1")
        check(Gen.alphaNumericStringBounded(8, 40)) { body =>
          val lines = List(
            disk(
              AcpUpdate.ToolCall(
                toolCallId = id,
                title = "Edit",
                kind = ToolKind.Edit,
                status = ToolStatus.Pending,
              )
            ),
            s"""{"method":"session/update","params":{"sessionId":"sess","update":{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","title":"Edit","content":[{"type":"content","content":"$body"}]}}}""",
          )
          val row = SessionLog.fold(lines).turns.head.tools.head
          assertTrue(row.id == id, row.status == ToolStatus.Completed, row.output.isEmpty, row.input.isEmpty)
        }
      },
    )

  private def disk(update: AcpUpdate, method: String = "session/update"): String =
    Json
      .Obj(
        "timestamp" -> Json.Num(1),
        "method"    -> Json.Str(method),
        "params"    -> AcpSessionNotify("sess", update).asJson,
      )
      .toJson

  private val genLine: Gen[Any, String] =
    Gen.oneOf(
      Gen.const(""),
      Gen.alphaNumericStringBounded(1, 24).map(t => disk(AcpUpdate.User(AcpContent.Text(t)))),
      Gen.alphaNumericStringBounded(1, 24).map(t => disk(AcpUpdate.Agent(AcpContent.Text(t)))),
      Gen.alphaNumericStringBounded(1, 24).map(t => disk(AcpUpdate.Thought(AcpContent.Text(t)))),
      Gen.const(
        disk(
          AcpUpdate.ToolCall(
            toolCallId = ToolCallId("c1"),
            title = "Read",
            kind = ToolKind.Read,
            status = ToolStatus.Pending,
          )
        )
      ),
    )

  private val genLines: Gen[Any, List[String]] = Gen.listOfBounded(0, 8)(genLine)
end SessionLogSpec
