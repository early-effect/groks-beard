package groksbeard.core

import zio.test.*

object ConfigTomlSpec extends ZIOSpecDefault:
  def spec =
    suite("ConfigToml")(
      test("set inserts a [ui] table") {
        val next = ConfigToml.set("", "ui", "theme", "tokyonight")
        assertTrue(
          next.contains("[ui]"),
          next.contains("theme = \"tokyonight\""),
          ConfigToml.get(next, "ui", "theme").contains("tokyonight"),
        )
      },
      test("set patches a key without clobbering neighbors") {
        val src  = "[ui]\nvim_mode = true\n\n[mcp_servers.x]\ncommand = \"n\"\n"
        val next = ConfigToml.set(src, "ui", "theme", "groknight")
        assertTrue(
          ConfigToml.get(next, "ui", "vim_mode").contains("true"),
          ConfigToml.get(next, "ui", "theme").contains("groknight"),
          next.contains("[mcp_servers.x]"),
          next.contains("command = \"n\""),
        )
      },
    )
end ConfigTomlSpec
