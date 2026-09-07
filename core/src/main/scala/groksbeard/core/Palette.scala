package groksbeard.core

enum PaletteKind:
  case Slash(name: String)
  case Mcps
  case Todos
  case Settings
  case SessionInfo
  case Context

final case class PaletteRow(
    id: String,
    label: String,
    hint: String,
    description: String,
    kind: PaletteKind,
)

object Palette:
  private val Aliases = Set("clear", "title", "undo", "mcp", "status", "info")

  val TodosRow: PaletteRow =
    PaletteRow("todos", "Todos", "Ctrl+T", "Toggle the session todo list", PaletteKind.Todos)

  val SettingsRow: PaletteRow =
    PaletteRow("settings", "Settings", "", "Composer and inclusion settings", PaletteKind.Settings)

  val McpsRow: PaletteRow =
    PaletteRow(
      "mcps",
      "MCP Servers",
      "/mcps",
      "View and toggle MCP servers",
      PaletteKind.Mcps,
    )

  val SessionInfoRow: PaletteRow =
    PaletteRow(
      "session-info",
      "Session info",
      "/session-info",
      "Session id, model, turns, and context usage",
      PaletteKind.SessionInfo,
    )

  val ContextRow: PaletteRow =
    PaletteRow(
      "context",
      "Context",
      "/context",
      "How the context window is being used",
      PaletteKind.Context,
    )

  def rows(commands: List[SlashCommand]): List[PaletteRow] =
    val advertised = SessionCommands.merge(commands).map(slashRow)
    val extra      = List(McpsRow, TodosRow, SettingsRow).filterNot(r => advertised.exists(_.id == r.id))
    val merged     = advertised ++ extra
    val seen       = scala.collection.mutable.LinkedHashSet.empty[String]
    val unique     = merged.filter { row =>
      if Aliases.contains(row.id) || !seen.add(row.id) then false
      else true
    }
    val pin  = List("mcps", "new", "resume", "home")
    val head = pin.flatMap(id => unique.find(_.id == id))
    val tail = unique.filterNot(r => pin.contains(r.id))
    head ++ tail
  end rows

  def filter(rows: List[PaletteRow], query: String): List[PaletteRow] =
    val q = query.stripPrefix("/").trim.toLowerCase
    if q.isEmpty then rows
    else
      val prefix = rows.filter(r => hay(r).startsWith(q) || r.id.startsWith(q) || r.label.toLowerCase.startsWith(q))
      val mid    = rows.filter { r =>
        val h = hay(r)
        !prefix.contains(r) && (h.contains(q) || r.id.contains(q) || r.label.toLowerCase.contains(q))
      }
      val desc = rows.filter { r =>
        !prefix.contains(r) && !mid.contains(r) && r.description.toLowerCase.contains(q)
      }
      prefix ++ mid ++ desc
    end if
  end filter

  def slashRow(cmd: SlashCommand): PaletteRow =
    val kind =
      if SessionCommands.isMcps(cmd.name) then PaletteKind.Mcps
      else if SessionCommands.isSessionInfo(cmd.name) then PaletteKind.SessionInfo
      else if SessionCommands.isContext(cmd.name) then PaletteKind.Context
      else PaletteKind.Slash(cmd.name)
    val label =
      if SessionCommands.isMcps(cmd.name) then McpsRow.label
      else if SessionCommands.isSessionInfo(cmd.name) then SessionInfoRow.label
      else if SessionCommands.isContext(cmd.name) then ContextRow.label
      else cmd.name
    PaletteRow(
      id = cmd.name,
      label = label,
      hint = s"/${cmd.name}",
      description = cmd.description,
      kind = kind,
    )
  end slashRow

  private def hay(row: PaletteRow): String =
    s"${row.id} ${row.label} ${row.hint}".toLowerCase
end Palette
