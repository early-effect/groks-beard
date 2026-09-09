package groksbeard.core

import zio.test.*

object MarkdownSpec extends ZIOSpecDefault:
  def spec =
    suite("Markdown")(
      test("parses a GFM table, ordered list, and grouped quote") {
        val blocks = Markdown.parse(
          """> first
            |> second
            |
            |1. alpha
            |2. beta
            |
            || What | Value |
            || --- | --- |
            || module | 3.9.0 |
            || fallback | 3.8.4 |
            |""".stripMargin
        )
        val quote = blocks.collectFirst { case Markdown.Block.Quote(in) =>
          in.collect { case Markdown.Inline.Text(t) => t }.mkString
        }
        val ordered = blocks.collectFirst { case Markdown.Block.Ordered(items) => items.size }
        val table   = blocks.collectFirst { case Markdown.Block.Table(h, rows) => (h.size, rows.size) }
        assertTrue(
          quote.contains("first second"),
          ordered.contains(2),
          table.contains((2, 2)),
        )
      },
      test("a pipe row without a separator is a paragraph") {
        val lone                                         = Markdown.parse("| a | b |")
        val ragged                                       = Markdown.parse("| Feature | Status |\n| Fast | yes |")
        val sep                                          = Markdown.parse("| --- | --- |")
        def paras(blocks: List[Markdown.Block]): Boolean =
          blocks.nonEmpty && blocks.forall {
            case Markdown.Block.Paragraph(_) => true
            case _                           => false
          }
        assertTrue(paras(lone), paras(ragged), ragged.size == 2, paras(sep))
      },
      test("parses headings, fences, bullets, and inlines") {
        val blocks = Markdown.parse(
          """# Title
            |
            |Hello **bold** and `code`.
            |
            |- one
            |- two
            |
            |```scala
            |val x = 1
            |```
            |
            |[ok](https://example.com) [no](javascript:alert(1))
            |""".stripMargin
        )
        val hasHeading = blocks.exists {
          case Markdown.Block.Heading(1, Markdown.Inline.Text("Title") :: Nil) => true
          case _                                                               => false
        }
        val hasFence = blocks.exists {
          case Markdown.Block.Fence(Some("scala"), body) => body.contains("val x = 1")
          case _                                         => false
        }
        val hasBullet = blocks.exists {
          case Markdown.Block.Bullet(items) => items.size == 2
          case _                            => false
        }
        val inlines = blocks.collect { case Markdown.Block.Paragraph(in) => in }.flatten
        assertTrue(
          hasHeading,
          hasFence,
          hasBullet,
          inlines.exists {
            case Markdown.Inline.Link("https://example.com", "ok") => true
            case _                                                 => false
          },
          inlines.exists {
            case Markdown.Inline.Text(t) => t.contains("javascript:alert")
            case _                       => false
          },
          !Markdown.allowedHref("javascript:alert(1)"),
        )
      },
      test("Thought headline truncates") {
        val long = "x" * 80
        assertTrue(
          Thought.summaryLabel("first\nsecond", done = false).startsWith("Thinking: first"),
          Thought.headline(long).endsWith("..."),
        )
      },
      test("streamParts keeps an unfinished line out of markdown") {
        val (done, tail) = Markdown.streamParts("Hello **bo")
        assertTrue(
          done.isEmpty,
          tail == "Hello **bo",
        )
      },
      test("streamParts holds a growing paragraph until a blank line") {
        val (done, tail) = Markdown.streamParts("Hello world\nstill gro")
        assertTrue(done.isEmpty, tail == "Hello world\nstill gro")
      },
      test("streamParts parses closed lines and leaves the growing last line") {
        val (done, tail) = Markdown.streamParts("# Title\n\nHello **bo")
        val heading      = done.exists {
          case Markdown.Block.Heading(1, Markdown.Inline.Text("Title") :: Nil) => true
          case _                                                               => false
        }
        assertTrue(heading, tail == "Hello **bo")
      },
      test("streamParts holds an open fence as tail") {
        val (done, tail) = Markdown.streamParts("Intro\n\n```scala\nval x = 1")
        assertTrue(
          done.exists {
            case Markdown.Block.Paragraph(_) => true
            case _                           => false
          },
          tail.startsWith("```scala"),
          tail.contains("val x = 1"),
        )
      },
      test("streamParts committed prefix is append-only as text grows") {
        val start             = "# Title\n\nHello **bo"
        val (done0, tail0)    = Markdown.streamParts(start)
        val (more, tail1)     = Markdown.pull(tail0, "ld**\n\nNext")
        val (doneFull, tailF) = Markdown.streamParts(start + "ld**\n\nNext")
        val heading           = done0.exists {
          case Markdown.Block.Heading(1, Markdown.Inline.Text("Title") :: Nil) => true
          case _                                                               => false
        }
        val para = more.exists {
          case Markdown.Block.Paragraph(in) =>
            in.exists {
              case Markdown.Inline.Strong("bold") => true
              case _                              => false
            }
          case _ => false
        }
        assertTrue(
          heading,
          para,
          tail1 == "Next",
          done0 ++ more == doneFull,
          tail1 == tailF,
        )
      },
      test("tool tail splits after four") {
        val tools              = List(1, 2, 3, 4, 5, 6)
        val (earlier, visible) = ToolView.splitTail(tools)
        assertTrue(earlier == List(1, 2), visible == List(3, 4, 5, 6), ToolView.rollupLabel(2) == "2 earlier tools")
      },
    )
end MarkdownSpec
