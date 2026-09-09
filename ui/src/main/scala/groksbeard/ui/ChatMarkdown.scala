package groksbeard.ui

import ascent.*
import ascent.css.CssClass
import ascent.css.Styles.*
import ascent.dsl.*
import ascent.dsl.Arg
import groksbeard.core.Markdown
import groksbeard.core.Markdown.{Block, Inline, ListItem}
import groksbeard.core.TurnView
import zio.Chunk

object ChatMarkdown:
  object TableWrap
      extends CssClass(
        overflowX.auto,
        maxWidth.pct(100),
      )

  def parts(turn: TurnView): (Seq[(String, Block)], String) =
    val (done, tail) =
      if turn.stopReason.isEmpty then Markdown.streamParts(turn.agent)
      else (Chunk.fromIterable(Markdown.parse(turn.agent)), "")
    (done.zipWithIndex.map((b, i) => (s"$i", b)).toSeq, tail)

  def render(text: String, live: Boolean = false): ascent.ast.UI[Any] =
    val (done, tail) =
      if live then Markdown.streamParts(text)
      else (Chunk.fromIterable(Markdown.parse(text)), "")
    val nodes =
      done.zipWithIndex.map((b, i) => block(b, i.toString)).toSeq ++
        (if tail.isEmpty then Seq.empty else Seq(tailEl(tail)))
    E.div(Arg.ArgsArg(nodes.map(Arg.ChildArg(_))))

  def tailEl(tail: String): ascent.ast.UI[Any] =
    if tail.startsWith("```") then E.pre(E.code(tail))
    else E.p(tail)

  def block(b: Block, key: String = "0"): ascent.ast.UI[Any] =
    b match
      case Block.Paragraph(in)  => E.p(in.map(inline)*)
      case Block.Heading(1, in) => E.h1(in.map(inline)*)
      case Block.Heading(2, in) => E.h2(in.map(inline)*)
      case Block.Heading(_, in) => E.h3(in.map(inline)*)
      case Block.Fence(_, body) => E.pre(E.code(body))
      case Block.Bullet(items)  =>
        E.ul(items.map(listItem)*)
      case Block.Ordered(items) =>
        E.ol(items.map(listItem)*)
      case Block.Quote(paras) =>
        E.blockquote(paras.map(p => E.p(p.map(inline)*))*)
      case Block.Table(headers, rows) =>
        E.div(
          TableWrap,
          TestId(s"md-table-$key"),
          E.table(
            E.thead(E.tr(headers.map(cell => E.th(cell.map(inline)*))*)),
            E.tbody(
              rows.zipWithIndex.map { (row, i) =>
                E.tr(
                  TestId(s"md-row-$key-$i"),
                  Arg.ArgsArg(row.map(cell => Arg.ChildArg(E.td(cell.map(inline)*)))),
                )
              }*
            ),
          ),
        )

  private def listItem(item: ListItem): ascent.ast.UI[Any] =
    val kids = item.inlines.map(inline) ++ item.children.map(c => block(c))
    E.li(Arg.ArgsArg(kids.map(Arg.ChildArg(_))))

  private def inline(n: Inline): ascent.ast.UI[Any] =
    n match
      case Inline.Text(value)       => E.span(value)
      case Inline.Code(value)       => E.code(value)
      case Inline.Strong(value)     => E.strong(value)
      case Inline.Em(value)         => E.em(value)
      case Inline.Link(href, label) =>
        E.a(A.href(href), A.target("_blank"), A.rel("noopener noreferrer"), label)
end ChatMarkdown
