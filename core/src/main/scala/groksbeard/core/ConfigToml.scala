package groksbeard.core

/** Patch `$GROK_HOME/config.toml` the way `/settings` does: user-initiated, one key at a time, no install writes. */
object ConfigToml:
  def userPath(home: String): String =
    val root = home.replaceAll("[\\\\/]+$", "")
    s"$root/config.toml"

  def get(text: String, table: String, key: String): Option[String] =
    val rows = tableBody(text, table)
    rows.flatMap { body =>
      body.linesIterator
        .map(_.trim)
        .find(l => l.startsWith(s"$key ") || l.startsWith(s"$key=") || l.startsWith(s"$key\t"))
        .map { line =>
          val raw = line.drop(line.indexOf('=') + 1).trim
          unquote(raw)
        }
    }
  end get

  def set(text: String, table: String, key: String, value: String): String =
    val rendered = s"$key = ${quote(value)}"
    tableRange(text, table) match
      case None =>
        val block   = s"[$table]\n$rendered\n"
        val trimmed = text.replaceAll("\\s+$", "")
        if trimmed.isEmpty then block else s"$trimmed\n\n$block"
      case Some((start, end)) =>
        val headerEnd =
          val nl = text.indexOf('\n', start)
          if nl < 0 || nl >= end then end else nl + 1
        val body = text.substring(headerEnd, end)
        val next = replaceKey(body, key, rendered)
        text.substring(0, headerEnd) + next + text.substring(end)
    end match
  end set

  def setBool(text: String, table: String, key: String, value: Boolean): String =
    set(text, table, key, if value then "true" else "false")

  def quote(value: String): String =
    val t = value.trim
    if t == "true" || t == "false" || t.matches("-?\\d+(\\.\\d+)?") then t
    else
      val escaped = t.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case c    => c.toString
      }
      s"\"$escaped\""
  end quote

  private def unquote(raw: String): String =
    val t = raw.trim
    if t.length >= 2 && ((t.head == '"' && t.last == '"') || (t.head == '\'' && t.last == '\'')) then
      t.substring(1, t.length - 1)
    else t

  private def replaceKey(body: String, key: String, rendered: String): String =
    val lines = body.split("\n", -1).toList
    val idx   = lines.indexWhere { l =>
      val t = l.trim
      t.startsWith(s"$key ") || t.startsWith(s"$key=") || t.startsWith(s"$key\t")
    }
    val next =
      if idx < 0 then
        val trimmed = lines.reverse.dropWhile(_.trim.isEmpty).reverse
        if trimmed.isEmpty then List(rendered, "")
        else trimmed :+ rendered :+ ""
      else lines.updated(idx, rendered)
    next.mkString("\n")
  end replaceKey

  private def tableBody(text: String, table: String): Option[String] =
    tableRange(text, table).map { (start, end) =>
      val nl = text.indexOf('\n', start)
      if nl < 0 || nl >= end then "" else text.substring(nl + 1, end)
    }

  private def tableRange(text: String, table: String): Option[(Int, Int)] =
    val needle = s"[$table]"
    val start  = indexAtLineStart(text, needle, 0)
    start.map { s =>
      var after = s + needle.length
      while after < text.length && (text.charAt(after) == ' ' || text.charAt(after) == '\t') do after += 1
      if after < text.length && text.charAt(after) == '\r' then after += 1
      if after < text.length && text.charAt(after) == '\n' then after += 1
      (s, nextTableOffset(text, after).getOrElse(text.length))
    }
  end tableRange

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
        while k < text.length && (text.charAt(k) == ' ' || text.charAt(k) == '\t') do k += 1
        if k < text.length && text.charAt(k) == '[' then return Some(i)
      i += 1
    None
  end nextTableOffset
end ConfigToml
