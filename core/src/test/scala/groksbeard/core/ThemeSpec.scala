package groksbeard.core

import zio.test.*

object ThemeSpec extends ZIOSpecDefault:
  def spec =
    suite("Theme")(
      test("canonical names") {
        assertTrue(
          Theme.canonicalize("tokyo-night") == "tokyonight",
          Theme.canonicalize("dark") == "groknight",
          Theme.canonicalize("terminal") == "vscode",
          Theme.pick("Rose Pine").exists(_.id == "rosepine"),
        )
      },
      test("cycle walks the list") {
        val ids  = Theme.All.map(_.id)
        val next = Theme.cycle(ids.head).id
        val wrap = Theme.cycle(ids.last).id
        assertTrue(next == ids(1), wrap == ids.head)
      },
    )
end ThemeSpec
