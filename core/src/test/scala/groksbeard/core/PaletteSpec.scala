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
          Palette.filter(rows, "context").headOption.exists(_.kind == PaletteKind.Context),
          Palette.filter(rows, "context").exists(_.id == "compact"),
          Palette.filter(rows, "info").exists(_.kind == PaletteKind.SessionInfo),
        )
      },
      test("empty advertised still lists pager builtins and MCP") {
        val rows = Palette.rows(Nil)
        assertTrue(rows.map(_.id).take(4) == List("mcps", "new", "resume", "home"))
      },
      test("/mcps is a client command") {
        assertTrue(
          SessionCommands.intercept("/mcps").contains(ClientCommand("mcps")),
          SessionCommands.intercept("/mcp").contains(ClientCommand("mcp")),
          SessionCommands.isMcps("mcps"),
          SessionCommands.merge(Nil).exists(_.name == "mcps"),
        )
      },
      test("/session-info and /context are client commands") {
        val merged = SessionCommands.merge(Nil)
        val rows   = Palette.rows(merged)
        assertTrue(
          SessionCommands.intercept("/session-info").contains(ClientCommand("session-info")),
          SessionCommands.intercept("/status").contains(ClientCommand("status")),
          SessionCommands.intercept("/info").contains(ClientCommand("info")),
          SessionCommands.intercept("/context").contains(ClientCommand("context")),
          SessionCommands.isSessionInfo("status"),
          SessionCommands.isContext("context"),
          merged.exists(_.name == "session-info"),
          merged.exists(_.name == "context"),
          !merged.exists(_.name == "status"),
          rows.exists(_.kind == PaletteKind.SessionInfo),
          rows.exists(_.kind == PaletteKind.Context),
          !rows.exists(_.id == "status"),
          !rows.exists(_.id == "info"),
        )
      },
    )
end PaletteSpec
