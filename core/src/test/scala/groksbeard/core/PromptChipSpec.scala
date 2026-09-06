package groksbeard.core

import zio.test.*
import zio.test.Gen

object PromptChipSpec extends ZIOSpecDefault:
  def spec =
    suite("PromptChip")(
      test("formats a file chip as @path") {
        val chip = PromptChip.fromFile("/abs/src/Foo.scala", Some("/abs"))
        assertTrue(PromptChip.formatAtRef(chip) == "@src/Foo.scala")
      },
      test("formats a selection as @path:start-end") {
        val chip = PromptChip.fromSelection("/abs/src/Foo.scala", Some("/abs"), Some(10), Some(50))
        assertTrue(PromptChip.formatAtRef(chip) == "@src/Foo.scala:10-50")
      },
      test("builds a TUI-shaped prompt from selection chips") {
        val selection = PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50))
        val quoted    = PromptChip.fromSelection(
          "/repo/plan.md",
          Some("/repo"),
          Some(12),
          Some(40),
          languageId = Some("markdown"),
          excerpt = Some("Use Metals for compile."),
        )
        val active = PromptChip.fromFile("/repo/src/Bar.scala", Some("/repo"), source = "active")
        assertTrue(
          PromptChip.buildPromptText("explain", List(selection)) == "@src/Foo.scala:10-50\n\nexplain",
          PromptChip.buildPromptText("why Metals?", List(quoted)) ==
            "@plan.md:12-40\n\n```markdown\nUse Metals for compile.\n```\n\nwhy Metals?",
          PromptChip.chipsForSend(Nil, Some(active), includeActiveFileByDefault = true) == List(active),
          PromptChip.chipsForSend(List(selection), Some(active), includeActiveFileByDefault = true) ==
            List(selection),
          PromptChip.sameRange(selection, selection.copy(endLine = Some(80))),
          PromptChip.upsert(List(selection), selection.copy(endLine = Some(80))).map(_.endLine) == List(Some(80)),
        )
      },
      test("truncates embeddings to the byte cap") {
        val truncated = Utf8.truncateToByteCap("a" * (40 * 1024), PromptChip.EmbedByteCap)
        assertTrue(Utf8.byteLength(truncated) <= PromptChip.EmbedByteCap)
      },
      test("workspaceRelativePath strips a matching root") {
        val seg = Gen.stringBounded(1, 8)(Gen.alphaNumericChar)
        check(seg, seg) { (dir, file) =>
          val root = s"/repo/$dir"
          val abs  = s"$root/$file.scala"
          val rel  = PromptChip.workspaceRelativePath(abs, Some(root))
          assertTrue(
            rel == s"$file.scala",
            PromptChip.formatAtRef(PromptChip.fromFile(abs, Some(root))).startsWith("@"),
          )
        }
      },
      test("chipsForSend keeps explicit chips and only then the active file") {
        val active = PromptChip.fromFile("/repo/src/Bar.scala", Some("/repo"), source = "active")
        val chip   = PromptChip.fromFile("/repo/src/Foo.scala", Some("/repo"))
        check(Gen.boolean) { include =>
          assertTrue(
            PromptChip.chipsForSend(List(chip), Some(active), include) == List(chip),
            PromptChip.chipsForSend(Nil, Some(active), include) == (if include then List(active) else Nil),
            PromptChip.chipsForSend(Nil, None, include).isEmpty,
          )
        }
      },
    )
end PromptChipSpec
