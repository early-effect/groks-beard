package groksbeard.core

import zio.test.*

object MarkdownSpec extends ZIOSpecDefault:
  def spec =
    suite("Markdown")(
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
      test("tool tail splits after four") {
        val tools              = List(1, 2, 3, 4, 5, 6)
        val (earlier, visible) = ToolView.splitTail(tools)
        assertTrue(earlier == List(1, 2), visible == List(3, 4, 5, 6), ToolView.rollupLabel(2) == "2 earlier tools")
      },
    )
end MarkdownSpec
