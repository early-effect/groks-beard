package groksbeard.core

/** Grok agent stderr. MCP AuthRequired and a down local server are notices; the agent is still up. */
object AgentLog:

  private val Csi  = "\u001b\\[[0-9;]*m".r
  private val Host = """mcp\.([a-z0-9-]+)\.com""".r
  private val Url  = """https?://[^"\s]+""".r

  def stripAnsi(text: String): String = Csi.replaceAllIn(text, "")

  def classify(text: String): Option[String] =
    val clean = stripAnsi(text)
    val lower = clean.toLowerCase
    if lower.contains("authrequired") || (lower.contains("invalid_token") && lower.contains("mcp")) then
      Some(authCopy(clean))
    else if mcpUnreachable(lower) then Some(LocalMcp.timeoutNotice(Url.findFirstIn(clean)))
    else None

  private def mcpUnreachable(lower: String): Boolean =
    val refused = lower.contains("connection refused") || lower.contains("connecterror")
    val mcp     = lower.contains("/mcp") || lower.contains("worker quit") ||
      lower.contains("transport channel closed")
    refused && mcp

  private def authCopy(text: String): String =
    val name = Host.findFirstMatchIn(text).map(_.group(1)).getOrElse("An MCP server")
    val who  = if name == "An MCP server" then name else s"$name MCP"
    s"$who needs authentication. Other tools still work."
end AgentLog
