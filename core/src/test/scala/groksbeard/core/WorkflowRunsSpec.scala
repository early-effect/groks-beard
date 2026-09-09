package groksbeard.core

import zio.test.*

object WorkflowRunsSpec extends ZIOSpecDefault:
  def spec =
    suite("WorkflowRuns")(
      test("verb keys map to pause resume stop") {
        assertTrue(
          WorkflowRuns.verbKey("p").contains("pause"),
          WorkflowRuns.verbKey("R").contains("resume"),
          WorkflowRuns.verbKey("x").contains("stop"),
          WorkflowRuns.verbKey("j").isEmpty,
        )
      },
      test("offers looks for the workflow slash command") {
        assertTrue(
          WorkflowRuns.offers(List(SlashCommand("workflow", "Launch or manage a workflow"))),
          !WorkflowRuns.offers(List(SlashCommand("compact", "Compact context"))),
        )
      },
      test("parseManage reads verb and handle") {
        assertTrue(
          WorkflowRuns.parseManage("pause review-changes").contains(("pause", "review-changes")),
          WorkflowRuns.parseManage("stop deep-research-2").contains(("stop", "deep-research-2")),
          WorkflowRuns.parseManage("runs").isEmpty,
          WorkflowRuns.parseManage("review-changes").isEmpty,
        )
      },
      test("step walks the run list") {
        val runs = List(WorkflowRun("a"), WorkflowRun("b"), WorkflowRun("c"))
        assertTrue(
          WorkflowRuns.clamp(runs, None).contains("a"),
          WorkflowRuns.step(runs, Some("a"), "ArrowDown").contains("b"),
          WorkflowRuns.step(runs, Some("a"), "End").contains("c"),
          WorkflowRuns.applyVerb(runs, "b", "pause").exists(r => r.name == "b" && r.paused),
        )
      },
    )
end WorkflowRunsSpec
