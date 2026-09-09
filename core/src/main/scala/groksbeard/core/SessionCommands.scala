package groksbeard.core

final case class ClientCommand(name: String, args: String = "")

object SessionCommands:
  val New: SlashCommand           = SlashCommand("new", "Start a new session")
  val Clear: SlashCommand         = SlashCommand("clear", "Start a new session")
  val Resume: SlashCommand        = SlashCommand("resume", "Resume a previous session")
  val Home: SlashCommand          = SlashCommand("home", "Return to the session list")
  val Model: SlashCommand         = SlashCommand("model", "Switch model")
  val Effort: SlashCommand        = SlashCommand("effort", "Set reasoning effort")
  val Rename: SlashCommand        = SlashCommand("rename", "Rename this session")
  val Title: SlashCommand         = SlashCommand("title", "Rename this session")
  val Delete: SlashCommand        = SlashCommand("delete", "Delete this session")
  val History: SlashCommand       = SlashCommand("history", "Search this session's prompts")
  val Copy: SlashCommand          = SlashCommand("copy", "Copy the last reply")
  val Export: SlashCommand        = SlashCommand("export", "Export this conversation")
  val Rewind: SlashCommand        = SlashCommand("rewind", "Rewind to an earlier turn")
  val Undo: SlashCommand          = SlashCommand("undo", "Rewind to an earlier turn")
  val Mcps: SlashCommand          = SlashCommand("mcps", "View and toggle MCP servers")
  val Mcp: SlashCommand           = SlashCommand("mcp", "View and toggle MCP servers")
  val SessionInfo: SlashCommand   = SlashCommand("session-info", "Session id, model, turns, and context usage")
  val Status: SlashCommand        = SlashCommand("status", "Session id, model, turns, and context usage")
  val Info: SlashCommand          = SlashCommand("info", "Session id, model, turns, and context usage")
  val Context: SlashCommand       = SlashCommand("context", "How the context window is being used")
  val Tasks: SlashCommand         = SlashCommand("tasks", "Background commands, loops, and monitors")
  val Loop: SlashCommand          = SlashCommand("loop", "Run a prompt on a recurring interval")
  val Fork: SlashCommand          = SlashCommand("fork", "Branch this session into a peer agent")
  val ViewPlan: SlashCommand      = SlashCommand("view-plan", "Open the saved plan")
  val ShowPlan: SlashCommand      = SlashCommand("show-plan", "Open the saved plan")
  val PlanView: SlashCommand      = SlashCommand("plan-view", "Open the saved plan")
  val Btw: SlashCommand           = SlashCommand("btw", "Ask an aside without interrupting the turn")
  val ConfigAgents: SlashCommand  = SlashCommand("config-agents", "Manage agent definitions and personas")
  val Agents: SlashCommand        = SlashCommand("agents", "Manage agent definitions and personas")
  val Personas: SlashCommand      = SlashCommand("personas", "Manage personas")
  val Dashboard: SlashCommand     = SlashCommand("dashboard", "Live roster of sessions")
  val AgentsDash: SlashCommand    = SlashCommand("agents-dashboard", "Live roster of sessions")
  val Sessions: SlashCommand      = SlashCommand("sessions", "Live roster of sessions")
  val Theme: SlashCommand         = SlashCommand("theme", "Switch color theme")
  val ThemeShort: SlashCommand    = SlashCommand("t", "Switch color theme")
  val CompactMode: SlashCommand   = SlashCommand("compact-mode", "Toggle compact chrome")
  val Minimal: SlashCommand       = SlashCommand("minimal", "Denser chrome")
  val Fullscreen: SlashCommand    = SlashCommand("fullscreen", "Restore full chrome")
  val Full: SlashCommand          = SlashCommand("full", "Restore full chrome")
  val VimMode: SlashCommand       = SlashCommand("vim-mode", "Toggle vim scrollback keys")
  val Doctor: SlashCommand        = SlashCommand("doctor", "Check this session")
  val Voice: SlashCommand         = SlashCommand("voice", "Dictate into the composer")
  val AlwaysApprove: SlashCommand = SlashCommand("always-approve", "Skip permission prompts")
  val Auto: SlashCommand          = SlashCommand("auto", "Auto permission mode")

  val All: List[SlashCommand] =
    List(
      New,
      Clear,
      Resume,
      Home,
      Model,
      Effort,
      Rename,
      Title,
      Delete,
      History,
      Copy,
      Export,
      Rewind,
      Mcps,
      SessionInfo,
      Context,
      Tasks,
      Loop,
      Fork,
      ViewPlan,
      Btw,
      ConfigAgents,
      Personas,
      Dashboard,
      Theme,
      CompactMode,
      Minimal,
      Fullscreen,
      VimMode,
      Doctor,
      Voice,
    )

  def merge(advertised: List[SlashCommand]): List[SlashCommand] =
    val names = advertised.map(_.name.toLowerCase).toSet
    advertised ++ All.filterNot(c => names.contains(c.name.toLowerCase))

  def isNew(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "new" || n == "clear"

  def isResume(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "resume"

  def isHome(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "home" || n == "welcome"

  def isModel(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "model" || n == "m"

  def isEffort(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "effort"

  def isRename(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "rename" || n == "title"

  def isDelete(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "delete"

  def isHistory(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "history"

  def isCopy(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "copy"

  def isExport(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "export"

  def isRewind(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "rewind" || n == "undo"

  def isMcps(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "mcps" || n == "mcp"

  def isSessionInfo(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "session-info" || n == "status" || n == "info"

  def isContext(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "context"

  def isTasks(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "tasks"

  def isLoop(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "loop"

  def isFork(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "fork"

  def isViewPlan(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "view-plan" || n == "show-plan" || n == "plan-view"

  def isBtw(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "btw"

  def isConfigAgents(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "config-agents" || n == "agents"

  def isPersonas(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "personas"

  def isDashboard(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "dashboard" || n == "agents-dashboard" || n == "sessions"

  def isTheme(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "theme" || n == "t"

  def isCompact(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "compact-mode" || n == "minimal"

  def isFullscreen(name: String): Boolean =
    val n = name.stripPrefix("/").toLowerCase
    n == "fullscreen" || n == "full"

  def isVim(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "vim-mode"

  def isDoctor(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "doctor"

  def isVoice(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "voice"

  def isAlwaysApprove(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "always-approve"

  def isAuto(name: String): Boolean =
    name.stripPrefix("/").toLowerCase == "auto"

  def isWorkflowRuns(name: String, args: String): Boolean =
    name.stripPrefix("/").toLowerCase == "workflow" && args.trim == "runs"

  def isWorkflowManage(name: String, args: String): Option[(String, String)] =
    if name.stripPrefix("/").toLowerCase != "workflow" then None
    else WorkflowRuns.parseManage(args)

  def intercept(text: String): Option[ClientCommand] =
    val trimmed = text.trim
    if !trimmed.startsWith("/") then None
    else
      val rest        = trimmed.drop(1)
      val i           = rest.indexWhere(_.isWhitespace)
      val (raw, args) =
        if i < 0 then (rest, "")
        else (rest.take(i), rest.drop(i).trim)
      val name = raw.toLowerCase
      if isNew(name) || isResume(name) || isHome(name) || isModel(name) || isEffort(name) || isRename(name) ||
        isDelete(name) || isHistory(name) || isCopy(name) || isExport(name) || isRewind(name) || isMcps(name) ||
        isSessionInfo(name) || isContext(name) || isTasks(name) || isLoop(name) || isFork(name) || isViewPlan(name) ||
        isBtw(name) || isConfigAgents(name) || isPersonas(name) || isDashboard(name) || isTheme(name) ||
        isCompact(name) || isFullscreen(name) || isVim(name) || isDoctor(name) || isVoice(name) ||
        isAlwaysApprove(name) || isAuto(name) || isWorkflowRuns(name, args) || isWorkflowManage(name, args).nonEmpty
      then Some(ClientCommand(name, args))
      else None
    end if
  end intercept
end SessionCommands
