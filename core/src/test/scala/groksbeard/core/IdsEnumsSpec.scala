package groksbeard.core

import zio.json.*
import zio.test.*

object IdsEnumsSpec extends ZIOSpecDefault:
  def spec = suite("ids and closed enums")(
    test("opaque ids round-trip as JSON strings") {
      val sid = SessionId("sess_1")
      val tid = TurnId.mint(3)
      assertTrue(
        SessionId.empty.isEmpty,
        SessionId("x").nonEmpty,
        !ModeId.Normal.isEmpty,
        sid.toJson == "\"sess_1\"",
        "\"sess_1\"".fromJson[SessionId] == Right(sid),
        tid.toJson == "\"turn_3\"",
        TerminalId.mint(1).toJson == "\"term-1\"",
        """{"terminalId":""}""".fromJson[TerminalCreateResult].isLeft,
      )
    },
    test("closed enums round-trip as JSON strings") {
      assertTrue(
        TodoStatus.InProgress.toJson == "\"in_progress\"",
        "\"done\"".fromJson[TodoStatus] == Right(TodoStatus.Completed),
        ToolKind.Edit.toJson == "\"edit\"",
        "\"bash\"".fromJson[ToolKind] == Right(ToolKind.Execute),
        "\"failed\"".fromJson[ToolStatus] == Right(ToolStatus.Failed),
        StopReason.EndTurn.toJson == "\"end_turn\"",
        PlanOutcome.Approved.toJson == "\"approved\"",
        "\"nope\"".fromJson[PlanOutcome].isLeft,
        ChangeKind.Modify.toJson == "\"modify\"",
        ChipSource.Mention.toJson == "\"mention\"",
      )
    },
  )
end IdsEnumsSpec
