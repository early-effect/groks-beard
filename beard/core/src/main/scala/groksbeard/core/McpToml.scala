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

  // Scala.js regex has no MULTILINE; scan line starts instead.
  private def tableRange(text: String, header: String): Option[(Int, Int)] =
    val needle = s"[$header]"
    val start  = indexAtLineStart(text, needle, 0)
    start.map { s =>
      var after = s + needle.length
      while after < text.length && isHSpace(text.charAt(after)) do after += 1
      if after < text.length && text.charAt(after) == '\r' then after += 1
      if after < text.length && text.charAt(after) == '\n' then after += 1
      val next = nextTableOffset(text, after)
      (s, next.getOrElse(text.length))
    }
  end tableRange

  private def isHSpace(c: Char): Boolean = c == ' ' || c == '\t'

  private def indexAtLineStart(text: String, needle: String, from: Int): Option[Int] =
    var i = from
    while i <= text.length do
      val idx = text.indexOf(needle, i)
      if idx < 0 then return None
      if idx == 0 || text.charAt(idx - 1) == '\n' then return Some(idx)
      i = idx + 1
    None

  private def nextTableOffset(text: String, from: Int): Option[Int] =
    var i = from
    while i < text.length do
      val atLine = i == 0 || text.charAt(i - 1) == '\n'
      if atLine then
        var k = i
        while k < text.length && isHSpace(text.charAt(k)) do k += 1
        if k < text.length && text.charAt(k) == '[' then return Some(i)
      i += 1
    None
  end nextTableOffset
end McpToml
