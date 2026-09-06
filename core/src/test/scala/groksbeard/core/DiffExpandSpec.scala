package groksbeard.core

import zio.test.*
import zio.test.Gen

object DiffExpandSpec extends ZIOSpecDefault:
  def spec =
    suite("DiffExpand")(
      test("substitutes a region in the middle of a file at permission time") {
        val disk  = "aaa\nold-token\nccc\n"
        val sides = DiffExpand.expand(
          DiffExpandInput(Some(disk), "old-token", "new-token", diskIsBefore = true)
        )
        assertTrue(
          sides.wholeFile,
          sides.oldText == disk,
          sides.newText == "aaa\nnew-token\nccc\n",
          sides.firstChangedLine == 1,
        )
      },
      test("treats empty oldRegion plus non-empty disk as a whole-file write") {
        val disk  = "keep me"
        val sides = DiffExpand.expand(DiffExpandInput(Some(disk), "", "replaced", diskIsBefore = true))
        assertTrue(sides.wholeFile, sides.oldText == disk, sides.newText == "replaced")
      },
      test("falls back to region-only when the file is missing") {
        val sides = DiffExpand.expand(DiffExpandInput(None, "old", "new", diskIsBefore = true))
        assertTrue(!sides.wholeFile, sides.oldText == "old", sides.newText == "new")
      },
      test("treats a missing disk plus empty oldRegion as a new file") {
        val sides = DiffExpand.expand(DiffExpandInput(None, "", "hello", diskIsBefore = true))
        assertTrue(sides.wholeFile, sides.oldText == "", sides.newText == "hello")
      },
      test("falls back to region-only when the file is oversize") {
        val disk  = "x" * (DiffExpand.MaxBytes + 1)
        val sides = DiffExpand.expand(DiffExpandInput(Some(disk), "x", "y", diskIsBefore = true))
        assertTrue(!sides.wholeFile)
      },
      test("does not manufacture a CRLF diff when the region arrived with LF") {
        val disk  = "aaa\r\nold\r\nccc\r\n"
        val sides = DiffExpand.expand(DiffExpandInput(Some(disk), "old", "new", diskIsBefore = true))
        assertTrue(sides.wholeFile, sides.newText.contains("\r\n"), sides.newText == "aaa\r\nnew\r\nccc\r\n")
      },
      test("replaceAll substitutes every match") {
        val sides = DiffExpand.expand(
          DiffExpandInput(Some("foo bar foo"), "foo", "baz", diskIsBefore = true, replaceAll = true)
        )
        assertTrue(sides.newText == "baz bar baz")
      },
      test("replaceAll with an empty needle leaves the disk alone") {
        val disk  = "keep"
        val sides = DiffExpand.expand(
          DiffExpandInput(Some(disk), "", "x", diskIsBefore = true, replaceAll = true)
        )
        assertTrue(sides.oldText == disk, sides.newText == "x")
      },
      test("sites pick the matching 1-based line among identical tokens") {
        val disk  = "foo\nfoo\n"
        val sides = DiffExpand.expand(
          DiffExpandInput(
            Some(disk),
            "foo",
            "bar",
            diskIsBefore = true,
            sites = List(DiffSite("foo", "bar", oldLine = Some(2))),
          )
        )
        assertTrue(sides.newText == "foo\nbar\n")
      },
      test("recovers the original from a post-write disk") {
        val disk  = "aaa\nnew-token\nccc\n"
        val sides = DiffExpand.expand(
          DiffExpandInput(Some(disk), "old-token", "new-token", diskIsBefore = false)
        )
        assertTrue(sides.oldText == "aaa\nold-token\nccc\n", sides.newText == disk)
      },
      test("falls back to region-only when the needle is absent") {
        val sides = DiffExpand.expand(
          DiffExpandInput(Some("aaa\nbbb\n"), "missing", "new", diskIsBefore = true)
        )
        assertTrue(!sides.wholeFile, sides.oldText == "missing")
      },
      test("permission-time expansion is invertible via the post-write path") {
        val chunk = Gen.stringBounded(0, 24)(Gen.alphaNumericChar)
        check(chunk, chunk) { (prefix, suffix) =>
          val disk   = s"${prefix}OLD${suffix}"
          val before = DiffExpand.expand(DiffExpandInput(Some(disk), "OLD", "NEW", diskIsBefore = true))
          val after  = DiffExpand.expand(
            DiffExpandInput(Some(before.newText), "OLD", "NEW", diskIsBefore = false)
          )
          assertTrue(after.oldText == disk, after.newText == before.newText)
        }
      },
    )
end DiffExpandSpec
