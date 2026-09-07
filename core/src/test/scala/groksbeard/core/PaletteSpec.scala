package groksbeard.core

import zio.test.*

object PaletteSpec extends ZIOSpecDefault:
  def spec =
    suite("Palette")(
      test("rows pin MCP, new, resume, and home ahead of advertised slash") {
        val rows = Palette.rows(
          SessionCommands.merge(List(SlashCommand("compact", "Compact context")))
        )
        assertTrue(
          rows.head.kind == PaletteKind.Mcps,
          rows.map(_.id).take(4) == List("mcps", "new", "resume", "home"),
          rows.exists(_.id == "compact"),
          rows.exists(_.kind == PaletteKind.Mcps),
          rows.exists(_.kind == PaletteKind.Todos),
          rows.exists(_.kind == PaletteKind.Settings),
          !rows.exists(_.id == "clear"),
          !rows.exists(_.id == "undo"),
        )
      },
      test("filters prefix, then mid, then description") {
        val rows = Palette.rows(List(SlashCommand("compact", "Compact context")))
        assertTrue(
          Palette.filter(rows, "mcp").exists(_.kind == PaletteKind.Mcps),
          Palette.filter(rows, "todo").exists(_.kind == PaletteKind.Todos),
          Palette.filter(rows, "context").map(_.id).contains("compact"),
        )
      },
      test("/mcps is a client command") {
        assertTrue(
          SessionCommands.intercept("/mcps").contains(ClientCommand("mcps")),
          SessionCommands.intercept("/mcp").contains(ClientCommand("mcp")),
          SessionCommands.isMcps("mcps"),
          SessionCommands.merge(Nil).exists(_.name == "mcps"),
        )
      },
    )
end PaletteSpec
