package groksbeard.core

import zio.test.*

object UnifiedDiffSpec extends ZIOSpecDefault:
  def spec =
    suite("UnifiedDiff")(
      test("marks a replaced line as delete then add") {
        val rows = UnifiedDiff.lines("a\nb\nc", "a\nx\nc")
        assertTrue(
          rows == List(DiffLine.Context("a"), DiffLine.Del("b"), DiffLine.Add("x"), DiffLine.Context("c"))
        )
      },
      test("insert-only and delete-only") {
        assertTrue(
          UnifiedDiff.lines("a", "a\nb") == List(DiffLine.Context("a"), DiffLine.Add("b")),
          UnifiedDiff.lines("a\nb", "a") == List(DiffLine.Context("a"), DiffLine.Del("b")),
        )
      },
      test("fileName uses the last path segment") {
        assertTrue(
          UnifiedDiff.fileName("/tmp/src/Main.scala") == "Main.scala",
          UnifiedDiff.fileName("Foo.scala") == "Foo.scala",
        )
      },
    )
end UnifiedDiffSpec
