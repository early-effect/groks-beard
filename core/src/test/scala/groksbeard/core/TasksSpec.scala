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
      test("fold live subagent_spawned then subagent_finished") {
        val start = Tasks.fold(
          Json.Obj(
            "update" -> Json.Obj(
              "sessionUpdate" -> Json.Str("subagent_spawned"),
              "subagent_id"   -> Json.Str("01a0457f-9fa1-74e3-a271-4f0f4f9343cd"),
              "description"   -> Json.Str("[writer] Write design doc"),
              "subagent_type" -> Json.Str("general-purpose"),
              "model"         -> Json.Str("grok-4.6"),
            )
          ),
          Nil,
        )
        val done = start.flatMap { rows =>
          Tasks.fold(
            Json.Obj(
              "sessionUpdate" -> Json.Str("subagent_finished"),
              "subagent_id"   -> Json.Str("01a0457f-9fa1-74e3-a271-4f0f4f9343cd"),
              "status"        -> Json.Str("completed"),
            ),
            rows,
          )
        }
        assertTrue(
          start.exists(_.headOption.exists { r =>
            r.kind == TaskKind.Subagent &&
            r.status == TaskStatus.Running &&
            r.label == "[writer] Write design doc" &&
            r.detail == "general-purpose · grok-4.6"
          }),
          done.exists(_.headOption.exists(_.status == TaskStatus.Completed)),
        )
      },
      test("fold prefers role over subagent_type and accepts child_session_id") {
        val row = Tasks.fold(
          Json.Obj(
            "sessionUpdate"    -> Json.Str("subagent_spawned"),
            "child_session_id" -> Json.Str("child-1"),
            "description"      -> Json.Str("do the thing"),
            "role"             -> Json.Str("Implementer"),
            "subagent_type"    -> Json.Str("general-purpose"),
            "model"            -> Json.Str("grok-4.6"),
          ),
          Nil,
        )
        assertTrue(
          row.exists(_.headOption.exists { r =>
            r.id.value == "child-1" && r.detail == "Implementer · grok-4.6"
          })
        )
      },
      test("grouped lifts subagents above other work") {
        val rows = List(
          TaskRow("a", TaskKind.Command, TaskStatus.Running, "sbt compile"),
          TaskRow("s", TaskKind.Subagent, TaskStatus.Running, "Research spawn_subagent", "explore · grok-4.6"),
          TaskRow("b", TaskKind.Loop, TaskStatus.Running, "Check CI", "every 5m"),
        )
        val groups = Tasks.grouped(rows)
        assertTrue(
          groups.map(_._1) == List(Some("Subagents"), None),
          groups.head._2.map(_.id.value) == List("s"),
          groups(1)._2.map(_.id.value) == List("a", "b"),
        )
      },
      test("lifecycle copy and notices skip subagents") {
        val live = TaskRow("s", TaskKind.Subagent, TaskStatus.Running, "do the thing", "Implementer · grok-4.6")
        val done = live.copy(status = TaskStatus.Completed)
        val cmd  = TaskRow("c", TaskKind.Command, TaskStatus.Running, "sbt compile")
        assertTrue(
          Tasks.lifecycle(live) == "Subagent running: \"do the thing\" (Implementer · grok-4.6)",
          Tasks.lifecycle(done.copy(status = TaskStatus.Failed)) ==
            "Subagent failed: \"do the thing\" (Implementer · grok-4.6)",
          Tasks.notices(List(live), List(done)).isEmpty,
          Tasks.notices(List(cmd), List(cmd.copy(status = TaskStatus.Completed))).exists {
            case HostMsg.TaskNotice(text) => text.contains("Task completed")
            case _                        => false
          },
        )
      },
    )
end TasksSpec
