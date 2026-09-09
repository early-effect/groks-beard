package groksbeard.core

import zio.test.*

object CancelTurnSpec extends ZIOSpecDefault:
  def spec =
    suite("CancelTurn")(
      test("needs a panel only when live subagents and no memory") {
        val live = TaskRow(TaskId("s1"), TaskKind.Subagent, TaskStatus.Running, "go")
        val done = live.copy(status = TaskStatus.Completed)
        assertTrue(
          CancelTurn.needsPanel(List(live), None),
          !CancelTurn.needsPanel(List(live), Some(true)),
          !CancelTurn.needsPanel(List(done), None),
        )
      },
      test("1-4 map onto keep/stop and always remember") {
        assertTrue(
          CancelTurn.pick("1").contains(CancelChoice.StopRunning),
          !CancelTurn.keepChildren(CancelChoice.StopRunning),
          CancelTurn.keepChildren(CancelChoice.ContinueToRun),
          CancelTurn.remember(CancelChoice.AlwaysStop).contains(false),
          CancelTurn.remember(CancelChoice.AlwaysContinue).contains(true),
          CancelTurn.remember(CancelChoice.StopRunning).isEmpty,
        )
      },
    )
end CancelTurnSpec
