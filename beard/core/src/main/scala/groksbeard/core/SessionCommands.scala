package groksbeard.core

final case class ClientCommand(name: String, args: String = "")

object SessionCommands:
  val New: SlashCommand    = SlashCommand("new", "Start a new session")
  val Clear: SlashCommand  = SlashCommand("clear", "Start a new session")
  val Resume: SlashCommand = SlashCommand("resume", "Resume a previous session")
  val Home: SlashCommand   = SlashCommand("home", "Return to the session list")
  val Model: SlashCommand  = SlashCommand("model", "Switch model")

  val All: List[SlashCommand] = List(New, Clear, Resume, Home, Model)

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
      if isNew(name) || isResume(name) || isHome(name) || isModel(name) then Some(ClientCommand(name, args))
      else None
    end if
  end intercept
end SessionCommands
