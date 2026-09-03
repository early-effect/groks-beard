package groksbeard.core

/** Conservative markdown: escaped inlines, https/http/vscode links only. No HTML passthrough. */
object Markdown:

  enum Inline:
    case Text(value: String)
    case Code(value: String)
    case Strong(value: String)
    case Em(value: String)
    case Link(href: String, label: String)

  enum Block:
    case Paragraph(inlines: List[Inline])
    case Heading(level: Int, inlines: List[Inline])
    case Fence(lang: Option[String], body: String)
    case Bullet(items: List[List[Inline]])
    case Quote(inlines: List[Inline])

  def allowedHref(href: String): Boolean =
    href.startsWith("https://") || href.startsWith("http://") ||
      href.startsWith("vscode-file:") || href.startsWith("vscode-webview:")

  def parse(text: String): List[Block] =
    if text.isEmpty then Nil
    else parseBlocks(text.replace("\r\n", "\n").split("\n", -1).toList)

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
      case line :: rest if line.startsWith("> ") || line == ">" =>
        val body = if line == ">" then "" else line.drop(2)
        Block.Quote(inlines(body)) :: parseBlocks(rest)
      case line :: rest if isBullet(line) =>
        val (group, after) = (line :: rest).span(isBullet)
        val items          = group.map(l => inlines(l.replaceFirst("^[-*]\\s+", "")))
        Block.Bullet(items) :: parseBlocks(after)
      case line :: rest =>
        val (group, after) = (line :: rest).span(l =>
          l.trim.nonEmpty && !l.startsWith("```") && headingLevel(l).isEmpty && !isBullet(l) &&
            !l.startsWith("> ")
        )
        Block.Paragraph(inlines(group.mkString(" "))) :: parseBlocks(after)

  private def isBullet(line: String): Boolean =
    line.startsWith("- ") || line.startsWith("* ")

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
