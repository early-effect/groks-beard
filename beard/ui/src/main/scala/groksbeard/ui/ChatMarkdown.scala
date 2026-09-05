package groksbeard.ui

import ascent.*
import ascent.dsl.*
import groksbeard.core.Markdown
import groksbeard.core.Markdown.{Block, Inline}
import groksbeard.core.TurnView
import zio.Chunk

object ChatMarkdown:

  def parts(turn: TurnView): (Seq[(String, Block)], String) =
    val (done, tail) =
      if turn.stopReason.isEmpty then Markdown.streamParts(turn.agent)
      else (Chunk.fromIterable(Markdown.parse(turn.agent)), "")
    (done.zipWithIndex.map((b, i) => (s"$i", b)).toSeq, tail)

  def render(text: String, live: Boolean = false): ascent.ast.UI[Any] =
    val (done, tail) =
      if live then Markdown.streamParts(text)
      else (Chunk.fromIterable(Markdown.parse(text)), "")
    val nodes = done.map(block).toSeq ++ (if tail.isEmpty then Seq.empty else Seq(tailEl(tail)))
    E.div(Arg.ArgsArg(nodes.map(Arg.ChildArg(_))))

  def tailEl(tail: String): ascent.ast.UI[Any] =
    if tail.startsWith("```") then E.pre(E.code(tail))
    else E.p(tail)

  def block(b: Block): ascent.ast.UI[Any] =
    b match
      case Block.Paragraph(in)  => E.p(in.map(inline)*)
      case Block.Heading(1, in) => E.h1(in.map(inline)*)
      case Block.Heading(2, in) => E.h2(in.map(inline)*)
      case Block.Heading(_, in) => E.h3(in.map(inline)*)
      case Block.Fence(_, body) => E.pre(E.code(body))
      case Block.Bullet(items)  =>
        E.ul(items.map(item => E.li(item.map(inline)*))*)
      case Block.Quote(in) => E.blockquote(in.map(inline)*)

  private def inline(n: Inline): ascent.ast.UI[Any] =
    n match
      case Inline.Text(value)       => E.span(value)
      case Inline.Code(value)       => E.code(value)
      case Inline.Strong(value)     => E.strong(value)
      case Inline.Em(value)         => E.em(value)
      case Inline.Link(href, label) => E.a(A.href(href), label)
end ChatMarkdown
