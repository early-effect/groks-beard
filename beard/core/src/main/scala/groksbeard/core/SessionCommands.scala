package groksbeard.core

object SessionCommands:
  val New: SlashCommand    = SlashCommand("new", "Start a new session")
  val Clear: SlashCommand  = SlashCommand("clear", "Start a new session")
  val Resume: SlashCommand = SlashCommand("resume", "Resume a previous session")
  val Home: SlashCommand   = SlashCommand("home", "Return to the session list")

  val All: List[SlashCommand] = List(New, Clear, Resume, Home)

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

  def intercept(text: String): Option[String] =
    val trimmed = text.trim
    if !trimmed.startsWith("/") then None
    else
      val name = trimmed.drop(1).split("\\s", 2).headOption.getOrElse("").toLowerCase
      if isNew(name) || isResume(name) || isHome(name) then Some(name) else None
end SessionCommands
