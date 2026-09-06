package groksbeard.core

import zio.test.*

object GrokVersionSpec extends ZIOSpecDefault:
  def spec =
    suite("GrokVersion")(
      test("parses the 1.0.13 banner") {
        val v = GrokVersion.parse("grok 1.0.13 (5e9a58528b76) [stable]\n")
        assertTrue(
          v.exists(_.major == 1),
          v.exists(_.minor == 0),
          v.exists(_.patch == 13),
          v.exists(_.git.contains("5e9a58528b76")),
          v.exists(_.channel.contains("stable")),
        )
      },
      test("parses 1.0.3 and 1.0.4") {
        assertTrue(
          GrokVersion.parse("grok 1.0.3").exists(_.patch == 3),
          GrokVersion.parse("grok 1.0.4 (abc)").exists(_.patch == 4),
        )
      },
      test("returns none for unreadable and empty banners") {
        assertTrue(
          GrokVersion.parse("not a version").isEmpty,
          GrokVersion.parse("").isEmpty,
          GrokVersion.parse("   \n").isEmpty,
        )
      },
    )
end GrokVersionSpec
