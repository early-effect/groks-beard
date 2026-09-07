package groksbeard.core

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object RewindSpec extends ZIOSpecDefault:
  def spec =
    suite("Rewind")(
      test("fromTurns is one point per user prompt") {
        val turns = List(
          TurnView("t1", user = Some(TurnUser("first"))),
          TurnView("t2", agent = "no user"),
          TurnView("t3", user = Some(TurnUser("second\nmore"))),
        )
        val points = Rewind.fromTurns(turns)
        assertTrue(
          points.map(_.promptIndex) == List(0, 1),
          points.map(_.preview) == List("first", "second"),
        )
      },
      test("truncate keeps user turns through the chosen prompt") {
        val turns = List(
          TurnView("t1", user = Some(TurnUser("first")), agent = "a"),
          TurnView("t2", user = Some(TurnUser("second")), agent = "b"),
          TurnView("t3", user = Some(TurnUser("third")), agent = "c"),
        )
        val kept = Rewind.truncate(turns, 1)
        assertTrue(kept.map(_.id.value) == List("t1", "t2"))
      },
      test("decodePoints reads grok snake_case rewind points") {
        val json = Json.Obj(
          "points" -> Json.Arr(
            Json.Obj(
              "prompt_index"       -> Json.Num(2),
              "prompt_preview"     -> Json.Str("echo beard-terminal-probe"),
              "num_file_snapshots" -> Json.Num(3),
            )
          )
        )
        val points = Rewind.decodePoints(json)
        assertTrue(
          points == List(RewindPoint(2, "echo beard-terminal-probe", 3))
        )
      },
      test("execute params encode sessionId and prompt_index") {
        val json = RewindExecuteParams(SessionId("sess_1"), 2).toJson
        assertTrue(json.contains("\"sessionId\""), json.contains("\"prompt_index\""), json.contains("2"))
      },
    )
end RewindSpec
