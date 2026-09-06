package groksbeard.core

import zio.test.*

object PromptHistorySpec extends ZIOSpecDefault:
  private val model = ChatModel.empty.copy(
    turns = List(
      TurnView("t1", user = Some(TurnUser("first"))),
      TurnView("t2", user = Some(TurnUser("second look"))),
    ),
    queue = List(QueuedPrompt("q1", "queued now")),
  )

  def spec =
    suite("PromptHistory")(
      test("entries are newest first and include the queue") {
        assertTrue(PromptHistory.entries(model) == List("queued now", "second look", "first"))
      },
      test("query reads /history and a trailing filter") {
        assertTrue(
          PromptHistory.query("/history").contains(""),
          PromptHistory.query("  /history foo").contains("foo"),
          PromptHistory.query("/historyx").isEmpty,
          PromptHistory.query("/resume").isEmpty,
        )
      },
      test("filter prefers prefix then substring") {
        val rows = List("second look", "first", "look again")
        assertTrue(
          PromptHistory.filter(rows, "look") == List("look again", "second look"),
          PromptHistory.filter(rows, "").size == 3,
        )
      },
      test("older and newer clamp; newer of 0 closes") {
        assertTrue(
          PromptHistory.older(0, 3) == 1,
          PromptHistory.older(2, 3) == 2,
          PromptHistory.newer(0).isEmpty,
          PromptHistory.newer(2).contains(1),
        )
      },
      test("label takes the first line and clips") {
        assertTrue(
          PromptHistory.label("hello\nworld") == "hello",
          PromptHistory.label("a" * 80).length == 72,
        )
      },
    )
end PromptHistorySpec
