package groksbeard.ui

import ascent.*
import ascent.dsl.*
import groksbeard.core.Markdown
import groksbeard.core.Markdown.{Block, Inline}

object ChatMarkdown:

  def render(text: String): ascent.ast.UI[Any] =
    E.div(Markdown.parse(text).map(block)*)

  private def block(b: Block): ascent.ast.UI[Any] =
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
