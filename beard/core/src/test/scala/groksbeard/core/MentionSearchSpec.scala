package groksbeard.core

import zio.test.*

object MentionSearchSpec extends ZIOSpecDefault:
  def spec =
    suite("MentionSearch")(
      test("empty query has no glob") {
        assertTrue(MentionSearch.pattern("").isEmpty, MentionSearch.pattern("   ").isEmpty)
      },
      test("ranks basename prefix ahead of path match") {
        val files = List(
          MentionFile("docs/Foo.md", "/repo/docs/Foo.md"),
          MentionFile("src/Main.scala", "/repo/src/Main.scala"),
          MentionFile("src/foo/Bar.scala", "/repo/src/foo/Bar.scala"),
        )
        val ranked = MentionSearch.rank(files, "foo")
        assertTrue(ranked.head.path == "docs/Foo.md")
      },
    )
end MentionSearchSpec
