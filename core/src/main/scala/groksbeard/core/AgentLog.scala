package groksbeard.core

/** Grok agent stderr. MCP AuthRequired is a notice; the agent is still up. */
object AgentLog:

  private val Host = """mcp\.([a-z0-9-]+)\.com""".r

  def classify(text: String): Option[String] =
    val lower = text.toLowerCase
    if lower.contains("authrequired") || (lower.contains("invalid_token") && lower.contains("mcp")) then
      Some(authCopy(text))
    else None

  private def authCopy(text: String): String =
    val name = Host.findFirstMatchIn(text).map(_.group(1)).getOrElse("An MCP server")
    val who  = if name == "An MCP server" then name else s"$name MCP"
    s"$who needs authentication. Other tools still work."
end AgentLog
