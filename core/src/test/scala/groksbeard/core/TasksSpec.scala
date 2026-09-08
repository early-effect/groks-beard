package groksbeard.core

import zio.*
import zio.json.ast.Json
import zio.test.*

object TasksSpec extends ZIOSpecDefault:
  def spec =
    suite("Tasks")(
      test("parseLoop requires interval and prompt") {
        assertTrue(
          Tasks.parseLoop("").isLeft,
          Tasks.parseLoop("5m").isLeft,
          Tasks.parseLoop("5m check ci").exists(s => s.prompt == "check ci" && s.interval == 5.minutes),
          Tasks.parseLoop("30s ping").exists(_.interval == 60.seconds),
        )
      },
      test("fold task_backgrounded then task_completed") {
        val start = Tasks.fold(
          Json.Obj(
            "sessionUpdate" -> Json.Str("task_backgrounded"),
            "task_id"       -> Json.Str("t1"),
            "command"       -> Json.Str("sbt compile"),
            "description"   -> Json.Str("Compile"),
          ),
          Nil,
        )
        val done = start.flatMap { rows =>
          Tasks.fold(
            Json.Obj(
              "sessionUpdate" -> Json.Str("task_completed"),
              "task_snapshot" -> Json.Obj(
                "task_id" -> Json.Str("t1"),
                "command" -> Json.Str("sbt compile"),
              ),
            ),
            rows,
          )
        }
        assertTrue(
          start.exists(_.headOption.exists(r => r.id.value == "t1" && r.status == TaskStatus.Running)),
          done.exists(_.headOption.exists(_.status == TaskStatus.Completed)),
        )
      },
      test("fold scheduled_task_created and deleted") {
        val created = Tasks.fold(
          Json.Obj(
            "update" -> Json.Obj(
              "sessionUpdate"  -> Json.Str("scheduled_task_created"),
              "task_id"        -> Json.Str("loop-x"),
              "prompt"         -> Json.Str("Check CI"),
              "human_schedule" -> Json.Str("every 3 minutes"),
            )
          ),
          Nil,
        )
        val gone = created.flatMap { rows =>
          Tasks.fold(
            Json.Obj("sessionUpdate" -> Json.Str("scheduled_task_deleted"), "task_id" -> Json.Str("loop-x")),
            rows,
          )
        }
        assertTrue(
          created.exists(_.exists(r => r.kind == TaskKind.Loop && r.label == "Check CI")),
          gone.contains(Nil),
        )
      },
      test("statusLine names live kinds") {
        val rows = List(
          TaskRow("a", TaskKind.Command, TaskStatus.Running, "sbt compile"),
          TaskRow("b", TaskKind.Loop, TaskStatus.Running, "Check CI", "every 5m"),
          TaskRow("c", TaskKind.Command, TaskStatus.Completed, "done"),
        )
        assertTrue(Tasks.statusLine(rows).contains("1 command"), Tasks.statusLine(rows).contains("1 loop"))
      },
    )
end TasksSpec
