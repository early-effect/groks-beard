package groksbeard.core

import zio.test.*

object ModeLabelSpec extends ZIOSpecDefault:
  def spec =
    suite("ModeLabel")(
      test("maps known mode ids to display names") {
        assertTrue(
          ModeLabel.modeLabel("plan") == "Plan",
          ModeLabel.modeLabel("always-approve") == "Always approve",
          ModeLabel.modeLabel("auto") == "Auto",
          ModeLabel.modeLabel("normal", List(ModeOption("normal", "Ask"))) == "Ask",
        )
      },
      test("cycles Normal, Plan, Auto, Always-approve") {
        val modes = List(
          ModeOption("normal", "Normal"),
          ModeOption("auto", "Auto"),
          ModeOption("plan", "Plan"),
          ModeOption("always-approve", "Always approve"),
        )
        assertTrue(
          ModeLabel.nextMode("normal", modes) == "plan",
          ModeLabel.nextMode("plan", modes) == "auto",
          ModeLabel.nextMode("auto", modes) == "always-approve",
          ModeLabel.nextMode("always-approve", modes) == "normal",
        )
      },
      test("explains modes") {
        assertTrue(
          ModeLabel.modeTip("plan").contains("plan"),
          ModeLabel.modeTip("always-approve").contains("Skip permission"),
        )
      },
    )
end ModeLabelSpec
