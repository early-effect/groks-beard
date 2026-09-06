package groksbeard.core

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TodosSpec extends ZIOSpecDefault:
  def spec =
    suite("Todos")(
      test("ACP plan entries keep order and normalize status") {
        val raw = List(
          TodoEntry("Checkout branch", "in-progress", "HIGH", Some("1")),
          TodoEntry("Write tests", "pending"),
          TodoEntry("Ship it", "done", "low"),
        )
        val got = Todos.fromEntries(raw)
        assertTrue(
          got.map(_.content) == List("Checkout branch", "Write tests", "Ship it"),
          got.map(_.status) == List(Todos.InProgress, Todos.Pending, Todos.Completed),
          got.head.priority == "high",
          got.last.priority == "low",
          Todos.headline(got) == "Todos 1/3",
          Todos.mark(got.head.status) == "▶",
          Todos.mark(got.last.status) == "☑",
        )
      },
      test("plan.json object map becomes entries keyed by id") {
        val json =
          """{"todos":{"1":{"content":"Read ROADMAP","status":"completed"},"2":{"content":"Wire ACP plan","status":"in_progress","priority":"high"}}}"""
        val got = json.fromJson[Json].map(Todos.fromPlanJson).getOrElse(Nil)
        assertTrue(
          got.map(_.content) == List("Read ROADMAP", "Wire ACP plan"),
          got.map(_.id) == List(Some("1"), Some("2")),
          got.map(_.status) == List(Todos.Completed, Todos.InProgress),
          got(1).priority == "high",
        )
      },
      test("empty plan.json is no todos") {
        val json = """{"todos":{}}"""
        val got  = json.fromJson[Json].map(Todos.fromPlanJson).getOrElse(List(TodoEntry("nope")))
        assertTrue(got.isEmpty, Todos.headline(got) == "Todos")
      },
      test("todo_write array shape still decodes") {
        val json =
          """{"todos":[{"id":"1","content":"One","status":"in_progress"},{"id":"2","content":"Two"}]}"""
        val got = json.fromJson[Json].map(Todos.fromPlanJson).getOrElse(Nil)
        assertTrue(
          got.map(_.content) == List("One", "Two"),
          got.head.status == Todos.InProgress,
          got(1).status == Todos.Pending,
        )
      },
    )
end TodosSpec
