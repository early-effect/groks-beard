package groksbeard.core

enum PaletteKind:
  case Slash(name: String)
  case Mcps
  case Todos
  case Tasks
  case Settings
  case SessionInfo
  case Context
  case Agents
  case Dashboard
  case PlanView
  case Workflows
  case Doctor
  case Theme
  case Voice
end PaletteKind

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

  val TasksRow: PaletteRow =
    PaletteRow("tasks", "Tasks", "Ctrl+G", "Background commands, loops, and monitors", PaletteKind.Tasks)

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

  val AgentsRow: PaletteRow =
    PaletteRow("config-agents", "Manage Agents", "/config-agents", "Agent definitions and personas", PaletteKind.Agents)

  val DashboardRow: PaletteRow =
    PaletteRow("dashboard", "Dashboard", "/dashboard", "Live roster of sessions", PaletteKind.Dashboard)

  val PlanViewRow: PaletteRow =
    PaletteRow("view-plan", "View plan", "/view-plan", "Open the saved plan", PaletteKind.PlanView)

  val WorkflowsRow: PaletteRow =
    PaletteRow("workflow-runs", "Workflow runs", "/workflow runs", "Live workflow runs", PaletteKind.Workflows)

  val DoctorRow: PaletteRow =
    PaletteRow("doctor", "Doctor", "/doctor", "Check this session", PaletteKind.Doctor)

  val ThemeRow: PaletteRow =
    PaletteRow("theme", "Theme", "/theme", "Color theme", PaletteKind.Theme)

  val VoiceRow: PaletteRow =
    PaletteRow("voice", "Voice", "/voice", "Dictate into the composer", PaletteKind.Voice)

  def rows(commands: List[SlashCommand]): List[PaletteRow] =
    val advertised = SessionCommands.merge(commands).map(slashRow)
    val extra      =
      List(
        McpsRow,
        TodosRow,
        TasksRow,
        SettingsRow,
        AgentsRow,
        DashboardRow,
        PlanViewRow,
        WorkflowsRow,
        DoctorRow,
        ThemeRow,
        VoiceRow,
      )
        .filterNot(r => advertised.exists(_.id == r.id))
    val merged = advertised ++ extra
    val seen   = scala.collection.mutable.LinkedHashSet.empty[String]
    val unique = merged.filter { row =>
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
      else if SessionCommands.isTasks(cmd.name) then PaletteKind.Tasks
      else if SessionCommands.isConfigAgents(cmd.name) || SessionCommands.isPersonas(cmd.name) then PaletteKind.Agents
      else if SessionCommands.isDashboard(cmd.name) then PaletteKind.Dashboard
      else if SessionCommands.isViewPlan(cmd.name) then PaletteKind.PlanView
      else if SessionCommands.isDoctor(cmd.name) then PaletteKind.Doctor
      else if SessionCommands.isTheme(cmd.name) then PaletteKind.Theme
      else if SessionCommands.isVoice(cmd.name) then PaletteKind.Voice
      else PaletteKind.Slash(cmd.name)
    val label =
      if SessionCommands.isMcps(cmd.name) then McpsRow.label
      else if SessionCommands.isSessionInfo(cmd.name) then SessionInfoRow.label
      else if SessionCommands.isContext(cmd.name) then ContextRow.label
      else if SessionCommands.isTasks(cmd.name) then TasksRow.label
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
