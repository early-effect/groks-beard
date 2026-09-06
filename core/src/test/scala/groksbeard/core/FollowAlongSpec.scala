package groksbeard.core

import zio.test.*

object FollowAlongSpec extends ZIOSpecDefault:
  def spec =
    suite("FollowAlong")(
      test("pick takes the last location with a path and drops non-positive lines") {
        val locs = List(
          ToolLocation("/a.ts", Some(1)),
          ToolLocation("  ", Some(9)),
          ToolLocation("/b.ts", Some(0)),
          ToolLocation("/c.ts", Some(12)),
        )
        assertTrue(
          FollowAlong.pick(locs, "c1").contains(FollowTarget("/c.ts", Some(12), "c1")),
          FollowAlong.pick(List(ToolLocation("/b.ts", Some(0))), "c1").contains(FollowTarget("/b.ts", None, "c1")),
          FollowAlong.pick(Nil, "c1").isEmpty,
          FollowAlong.pick(List(ToolLocation("  ")), "c1").isEmpty,
        )
      },
      test("changed is false only for the same tool, path, and line") {
        val a = FollowTarget("/a.ts", Some(1), "c1")
        assertTrue(
          FollowAlong.changed(None, a),
          !FollowAlong.changed(Some(a), a),
          FollowAlong.changed(Some(a), a.copy(line = Some(2))),
          FollowAlong.changed(Some(a), a.copy(toolCallId = "c2")),
        )
      },
      test("a Beard diff in the active editor opens Beside") {
        assertTrue(
          FollowAlong.holdsDiff("beard-original"),
          FollowAlong.holdsDiff("beard-proposed"),
          !FollowAlong.holdsDiff("file"),
          FollowAlong.viewColumn(Some("beard-proposed")).contains(FollowAlong.BesideColumn),
          FollowAlong.viewColumn(Some("file")).isEmpty,
          FollowAlong.viewColumn(None).isEmpty,
        )
      },
    )
end FollowAlongSpec
