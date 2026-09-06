package groksbeard.core

import zio.test.*

object CliLocatorSpec extends ZIOSpecDefault:
  def spec =
    suite("CliLocator")(
      test("GROK_HOME wins over HOME") {
        assertTrue(GrokHome(Map("GROK_HOME" -> "/custom", "HOME" -> "/Users/russ").get) == "/custom")
      },
      test("HOME/.grok is the default unix home") {
        assertTrue(GrokHome(Map("HOME" -> "/Users/russ").get) == "/Users/russ/.grok")
      },
      test("USERPROFILE is the Windows home") {
        assertTrue(GrokHome(Map("USERPROFILE" -> "C:\\Users\\russ").get) == "C:\\Users\\russ/.grok")
      },
      test("setting wins when the file exists") {
        val got = CliLocator.locate(
          LocateGrok(
            cliPath = Some("/opt/grok"),
            env = Map("HOME" -> "/Users/russ").get,
            win = false,
            exists = _ == "/opt/grok",
          )
        )
        assertTrue(got == Right("/opt/grok"))
      },
      test("falls back to PATH grok") {
        val got = CliLocator.locate(
          LocateGrok(
            None,
            Map("GROK_HOME" -> "/custom/grok-home", "PATH" -> "/usr/bin:/hidden").get,
            win = false,
            exists = _ == "/hidden/grok",
          )
        )
        assertTrue(got == Right("/hidden/grok"))
      },
      test("resolves Windows .cmd shims to grok.exe") {
        val got = CliLocator.locate(
          LocateGrok(
            None,
            Map("USERPROFILE" -> "C:/Users/russ", "Path" -> "C:/tools").get,
            win = true,
            exists = p => p == "C:/tools/grok.cmd" || p == "C:/tools/grok.exe",
          )
        )
        assertTrue(got == Right("C:/tools/grok.exe"))
      },
      test("missing grok lists searched paths") {
        val got = CliLocator.locate(
          LocateGrok(None, Map("HOME" -> "/Users/russ").get, win = false, exists = _ => false)
        )
        assertTrue(got.swap.exists(_.exists(_.endsWith("/.grok/bin/grok"))))
      },
    )
end CliLocatorSpec
