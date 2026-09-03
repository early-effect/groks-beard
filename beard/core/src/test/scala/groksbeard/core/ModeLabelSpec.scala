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
      test("explains modes") {
        assertTrue(
          ModeLabel.modeTip("plan").contains("plan"),
          ModeLabel.modeTip("always-approve").contains("Skip permission"),
        )
      },
    )
end ModeLabelSpec
