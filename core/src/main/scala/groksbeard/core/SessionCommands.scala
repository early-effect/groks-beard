package groksbeard.core

final case class ClientCommand(name: String, args: String = "")

object SessionCommands:
  val New: SlashCommand         = SlashCommand("new", "Start a new session")
  val Clear: SlashCommand       = SlashCommand("clear", "Start a new session")
  val Resume: SlashCommand      = SlashCommand("resume", "Resume a previous session")
  val Home: SlashCommand        = SlashCommand("home", "Return to the session list")
  val Model: SlashCommand       = SlashCommand("model", "Switch model")
  val Effort: SlashCommand      = SlashCommand("effort", "Set reasoning effort")
  val Rename: SlashCommand      = SlashCommand("rename", "Rename this session")
  val Title: SlashCommand       = SlashCommand("title", "Rename this session")
  val Delete: SlashCommand      = SlashCommand("delete", "Delete this session")
  val History: SlashCommand     = SlashCommand("history", "Search this session's prompts")
  val Copy: SlashCommand        = SlashCommand("copy", "Copy the last reply")
  val Export: SlashCommand      = SlashCommand("export", "Export this conversation")
  val Rewind: SlashCommand      = SlashCommand("rewind", "Rewind to an earlier turn")
  val Undo: SlashCommand        = SlashCommand("undo", "Rewind to an earlier turn")
  val Mcps: SlashCommand        = SlashCommand("mcps", "View and toggle MCP servers")
  val Mcp: SlashCommand         = SlashCommand("mcp", "View and toggle MCP servers")
  val SessionInfo: SlashCommand = SlashCommand("session-info", "Session id, model, turns, and context usage")
  val Status: SlashCommand      = SlashCommand("status", "Session id, model, turns, and context usage")
  val Info: SlashCommand        = SlashCommand("info", "Session id, model, turns, and context usage")
  val Context: SlashCommand     = SlashCommand("context", "How the context window is being used")
  val Tasks: SlashCommand       = SlashCommand("tasks", "Background commands, loops, and monitors")
  val Loop: SlashCommand        = SlashCommand("loop", "Run a prompt on a recurring interval")
  val Fork: SlashCommand        = SlashCommand("fork", "Branch this session into a peer agent")

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
        isSessionInfo(name) || isContext(name) || isTasks(name) || isLoop(name) || isFork(name)
      then Some(ClientCommand(name, args))
      else None
    end if
  end intercept
end SessionCommands
