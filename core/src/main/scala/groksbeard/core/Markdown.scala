package groksbeard.core

import ascent.squawk.Eq
import zio.Chunk

/** Conservative markdown: escaped inlines, https/http/vscode links only. No HTML passthrough. */
object Markdown:

  enum Inline:
    case Text(value: String)
    case Code(value: String)
    case Strong(value: String)
    case Em(value: String)
    case Link(href: String, label: String)

  object Inline:
    given Eq[Inline] = (a, b) => a == b

  final case class ListItem(inlines: List[Inline], children: List[Block] = Nil)

  object ListItem:
    given Eq[ListItem] = (a, b) => a == b

  enum Block:
    case Paragraph(inlines: List[Inline])
    case Heading(level: Int, inlines: List[Inline])
    case Fence(lang: Option[String], body: String)
    case Bullet(items: List[ListItem])
    case Ordered(items: List[ListItem])
    case Quote(paragraphs: List[List[Inline]])
    case Table(headers: List[List[Inline]], rows: List[List[List[Inline]]])

  object Block:
    given Eq[Block] = (a, b) => a == b

  def allowedHref(href: String): Boolean =
    href.startsWith("https://") || href.startsWith("http://") ||
      href.startsWith("vscode-file:") || href.startsWith("vscode-webview:")

  def parse(text: String): List[Block] =
    if text.isEmpty then Nil
    else parseBlocks(text.replace("\r\n", "\n").split("\n", -1).toList)

  /** Closed blocks plus the open fence or trailing unterminated paragraph.
    *
    * The closed prefix is append-only as `text` grows, so a live fold can keep committed nodes and only rewrite the
    * tail. A finished table in the tail is peeled off so it does not sit as raw text until a blank line or stopReason.
    */
  def streamParts(text: String): (Chunk[Block], String) =
    if text.isEmpty then (Chunk.empty, "")
    else
      val lines = text.replace("\r\n", "\n").split("\n", -1).toList
      if fenceOpen(lines) then
        val start        = lines.lastIndexWhere(_.startsWith("```"))
        val (head, tail) = lines.splitAt(math.max(0, start))
        (Chunk.fromIterable(parseBlocks(head)), tail.mkString("\n"))
      else
        val lastBlank = lines.lastIndexWhere(_.trim.isEmpty)
        if lastBlank < 0 then peelTail(Nil, lines)
        else
          val (head, tail) = lines.splitAt(lastBlank + 1)
          if tail.forall(_.trim.isEmpty) then (Chunk.fromIterable(parseBlocks(lines)), "")
          else peelTail(head, tail)
      end if

  /** Fold the next chunk onto an open tail. Closed blocks from the tail are the new prefix. */
  def pull(tail: String, more: String): (Chunk[Block], String) =
    streamParts(tail + more)

  private def peelTail(head: List[String], tail: List[String]): (Chunk[Block], String) =
    val (tables, leftover) = peelClosedTables(tail)
    (Chunk.fromIterable(parseBlocks(head) ++ tables), leftover.mkString("\n"))

  private def peelClosedTables(lines: List[String]): (List[Block], List[String]) =
    lines match
      case h :: s :: rest if isTableRow(h) && isTableSep(s) =>
        val (body, after) = rest.span(isTableRow)
        val table         = Block.Table(cells(h).map(inlines), body.map(l => cells(l).map(inlines)))
        val (more, left)  = peelClosedTables(after)
        (table :: more, left)
      case _ => (Nil, lines)

  private def fenceOpen(lines: List[String]): Boolean =
    lines.count(_.startsWith("```")) % 2 == 1

  private def parseBlocks(lines: List[String]): List[Block] =
    lines match
      case Nil                               => Nil
      case line :: rest if line.trim.isEmpty =>
        parseBlocks(rest)
      case line :: rest if line.startsWith("```") =>
        val lang          = Option(line.drop(3).trim).filter(_.nonEmpty)
        val (body, after) = rest.span(l => !l.startsWith("```"))
        val leftover      = after.drop(1)
        Block.Fence(lang, body.mkString("\n")) :: parseBlocks(leftover)
      case line :: rest if line.startsWith("#") && headingLevel(line).isDefined =>
        val level = headingLevel(line).get
        val body  = line.dropWhile(_ == '#').trim
        Block.Heading(level, inlines(body)) :: parseBlocks(rest)
      case line :: rest if isQuote(line) =>
        val (group, after) = (line :: rest).span(isQuote)
        Block.Quote(quoteParagraphs(group)) :: parseBlocks(after)
      case line :: rest if listPrefix(line).isDefined =>
        val (group, after) = (line :: rest).span(l => listPrefix(l).isDefined)
        parseList(group) :: parseBlocks(after)
      case line :: rest if isTableRow(line) && rest.headOption.exists(isTableSep) =>
        val header        = cells(line).map(inlines)
        val (body, after) = rest.drop(1).span(isTableRow)
        val rows          = body.map(l => cells(l).map(inlines))
        Block.Table(header, rows) :: parseBlocks(after)
      case line :: rest =>
        val (group, after)    = (line :: rest).span(continuesParagraph)
        val (taken, leftover) =
          if group.isEmpty then (List(line), rest) else (group, after)
        Block.Paragraph(inlines(taken.mkString(" "))) :: parseBlocks(leftover)

  private def continuesParagraph(line: String): Boolean =
    line.trim.nonEmpty && !line.startsWith("```") && headingLevel(line).isEmpty &&
      listPrefix(line).isEmpty && !isQuote(line) && !isTableRow(line) && !isTableSep(line)

  private def listPrefix(line: String): Option[(Int, Boolean, String)] =
    val s   = line.replace("\t", "    ")
    val ind = s.takeWhile(_ == ' ').length
    val t   = s.drop(ind)
    if t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") then Some((ind, false, t.drop(2)))
    else
      val i = t.indexOf(". ")
      if i > 0 && t.take(i).forall(_.isDigit) then Some((ind, true, t.drop(i + 2)))
      else None

  private def parseList(lines: List[String]): Block =
    val ordered = listPrefix(lines.head).exists(_._2)
    val base    = listPrefix(lines.head).map(_._1).getOrElse(0)
    val items   = parseListItems(lines, base)
    if ordered then Block.Ordered(items) else Block.Bullet(items)

  private def parseListItems(lines: List[String], base: Int): List[ListItem] =
    lines match
      case Nil          => Nil
      case line :: rest =>
        listPrefix(line) match
          case Some((ind, _, content)) if ind == base =>
            val (nested, after) = rest.span(l => listPrefix(l).exists(_._1 > base))
            val children        = if nested.isEmpty then Nil else List(parseList(nested))
            ListItem(inlines(content), children) :: parseListItems(after, base)
          case _ => parseListItems(rest, base)

  private def isQuote(line: String): Boolean =
    line.startsWith("> ") || line == ">"

  private def quoteBody(line: String): String =
    if line == ">" then "" else line.drop(2)

  private def quoteParagraphs(lines: List[String]): List[List[Inline]] =
    val bodies = lines.map(quoteBody)
    splitWhen(bodies)(_.trim.isEmpty)
      .filter(_.exists(_.trim.nonEmpty))
      .map(g => inlines(g.mkString(" ")))

  private def splitWhen[A](xs: List[A])(sep: A => Boolean): List[List[A]] =
    xs match
      case Nil => Nil
      case _   =>
        val (chunk, rest) = xs.span(a => !sep(a))
        val next          = rest.dropWhile(sep)
        if chunk.isEmpty then splitWhen(next)(sep)
        else chunk :: splitWhen(next)(sep)

  private def isTableRow(line: String): Boolean =
    line.trim.contains('|') && cells(line).size >= 2 && !isTableSep(line)

  private def isTableSep(line: String): Boolean =
    val cs = cells(line)
    cs.size >= 2 && cs.forall(p => p.nonEmpty && p.forall(c => c == '-' || c == ':'))

  private def cells(line: String): List[String] =
    val t   = line.trim
    val cut =
      val a = if t.startsWith("|") then t.drop(1) else t
      if a.endsWith("|") then a.dropRight(1) else a
    if !cut.contains('|') && !t.contains('|') then Nil
    else cut.split("\\|", -1).map(_.trim).toList

  private def headingLevel(line: String): Option[Int] =
    val hashes = line.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= 6 && line.drop(hashes).startsWith(" ") then Some(hashes)
    else None

  def inlines(text: String): List[Inline] =
    if text.isEmpty then Nil
    else
      val code   = "`([^`]+)`".r
      val link   = "\\[([^\\]]+)\\]\\(([^)]+)\\)".r
      val strong = "\\*\\*([^*]+)\\*\\*".r
      val em     = "\\*([^*]+)\\*".r
      val hits   = List(
        code.findAllMatchIn(text).map(m => (m.start, m.end, Inline.Code(m.group(1)): Inline)),
        link.findAllMatchIn(text).map { m =>
          val href  = m.group(2)
          val label = m.group(1)
          val node  =
            if allowedHref(href) then Inline.Link(href, label) else Inline.Text(s"[$label]($href)")
          (m.start, m.end, node)
        },
        strong.findAllMatchIn(text).map(m => (m.start, m.end, Inline.Strong(m.group(1)): Inline)),
        em.findAllMatchIn(text).map(m => (m.start, m.end, Inline.Em(m.group(1)): Inline)),
      ).flatten.toList.sortBy(_._1)
      stitch(text, 0, nonOverlapping(hits))

  private def nonOverlapping(hits: List[(Int, Int, Inline)]): List[(Int, Int, Inline)] =
    hits.foldLeft(List.empty[(Int, Int, Inline)]) { (acc, hit) =>
      acc.lastOption match
        case Some((_, end, _)) if hit._1 < end => acc
        case _                                 => acc :+ hit
    }

  private def stitch(text: String, at: Int, hits: List[(Int, Int, Inline)]): List[Inline] =
    hits match
      case Nil =>
        val tail = text.substring(at)
        if tail.isEmpty then Nil else List(Inline.Text(tail))
      case (start, end, node) :: rest =>
        val prefix = text.substring(at, start)
        val head   = if prefix.isEmpty then Nil else List(Inline.Text(prefix))
        head ::: node :: stitch(text, end, rest)
end Markdown
