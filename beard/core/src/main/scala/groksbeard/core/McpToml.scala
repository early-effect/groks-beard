package groksbeard.core

object McpToml:
  val ServerTable       = "mcp_servers.groks-beard"
  val EditorDownMessage =
    "Open this workspace in VS Code or Cursor with Grok's Beard and run \"Grok's Beard: Enable TUI Bridge\"."
  val RefreshMessage =
    "TUI bridge enabled. In a running TUI press r in /mcps, or start a new TUI session. The server does not attach live."

  def projectConfigPath(workspaceRoot: String): String =
    val root = workspaceRoot.stripSuffix("/").stripSuffix("\\")
    s"$root/.grok/config.toml"

  def renderTable(nodeCommand: String, proxyPath: String, workspace: String): String =
    val cmd  = jsonString(nodeCommand)
    val prox = jsonString(proxyPath)
    val ws   = jsonString(workspace)
    s"[$ServerTable]\ncommand = $cmd\nargs = [$prox, \"--workspace\", $ws]\n"

  def mergeTable(existing: String, table: String): String =
    tableRange(existing, ServerTable) match
      case None =>
        val trimmed = existing.replaceAll("\\s+$", "")
        if trimmed.isEmpty then table else s"$trimmed\n\n$table"
      case Some((start, end)) =>
        val before = existing.substring(0, start).replaceAll("\\s+$", "")
        val after  = existing.substring(end).replaceAll("^\\s+", "")
        val parts  = List(before, table.replaceAll("\\s+$", ""), after).filter(_.nonEmpty)
        parts.mkString("\n\n") + "\n"

  def removeTable(existing: String): String =
    tableRange(existing, ServerTable) match
      case None               => existing
      case Some((start, end)) =>
        val before = existing.substring(0, start).replaceAll("\\s+$", "")
        val after  = existing.substring(end).replaceAll("^\\s+", "")
        if before.isEmpty then if after.isEmpty then "" else if after.endsWith("\n") then after else s"$after\n"
        else if after.isEmpty then s"$before\n"
        else s"$before\n\n${if after.endsWith("\n") then after else s"$after\n"}"

  private def jsonString(value: String): String =
    val escaped = value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case c    => c.toString
    }
    s"\"$escaped\""

  private def tableRange(text: String, header: String): Option[(Int, Int)] =
    val escaped = header.replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1")
    val re      = s"(?m)^\\[$escaped\\][ \\t]*\\r?\\n?".r
    re.findFirstMatchIn(text).map { m =>
      val start = m.start
      val after = m.end
      val rest  = text.substring(after)
      val next  = "(?m)^[ \\t]*\\[".r.findFirstMatchIn(rest)
      val end   = next.map(n => after + n.start).getOrElse(text.length)
      (start, end)
    }
  end tableRange
end McpToml
