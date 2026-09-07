package groksbeard.core

import zio.test.*

object SessionFactsSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionFacts")(
      test("info rows cover title, id, directory, model, mode, turns, and occupancy") {
        val model = ChatModel.empty.copy(
          sessionId = SessionId("sess_1"),
          title = "Queue pane",
          cwd = "/repo",
          modelId = ModelId("grok-4.6"),
          models = List(ModelOption(ModelId("grok-4.6"), "Grok 4.6")),
          effort = "high",
          modeId = ModeId.Plan,
          occupancy = Some(Occupancy(12_000, 500_000)),
          turns = List(TurnView(TurnId("t1"), user = Some(TurnUser("hi")))),
          mcps = List(McpServerView("metals", "http", "http://localhost", enabled = true)),
          inSession = true,
        )
        val rows = SessionFacts.info(model)
        val ids  = rows.map(_.id)
        assertTrue(
          SessionFacts.title(model) == "Queue pane",
          SessionFacts.sessionId(model).contains("sess_1"),
          SessionFacts.modelLine(model) == "Grok 4.6 · high",
          SessionFacts.turns(model) == 1,
          ids == List("title", "session", "directory", "model", "mode", "turns", "context", "mcp"),
          rows.find(_.id == "context").exists(_.value.contains("12k")),
          rows.find(_.id == "mcp").exists(_.value == "metals"),
          SessionFacts.block(SessionPane.Info, model).contains("sess_1"),
        )
      },
      test("context rows split used, free, and window without inventing categories") {
        val model = ChatModel.empty.copy(occupancy = Some(Occupancy(80, 100)), turns = List(TurnView(TurnId("t1"))))
        val rows  = SessionFacts.context(model)
        assertTrue(
          rows.map(_.id) == List("used", "free", "window", "turns"),
          rows.find(_.id == "used").exists(_.value == "80"),
          rows.find(_.id == "free").exists(_.value.contains("20")),
          rows.find(_.id == "window").exists(_.value == "100"),
          !SessionFacts.block(SessionPane.Context, model).toLowerCase.contains("system prompt"),
        )
      },
      test("context without occupancy says usage is unreported") {
        val rows = SessionFacts.context(ChatModel.empty)
        assertTrue(
          rows.head.id == "usage",
          rows.head.value.contains("not reported"),
        )
      },
      test("opaque session titles fall back to This session") {
        val id    = SessionId("01a04ead-8d8e-7e92-9824-3e8580203167")
        val model = ChatModel.empty.copy(sessionId = id, title = id.value, inSession = true)
        assertTrue(SessionFacts.title(model) == "This session")
      },
    )
end SessionFactsSpec
