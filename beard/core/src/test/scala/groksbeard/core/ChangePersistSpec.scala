package groksbeard.core

import zio.test.*

object ChangePersistSpec extends ZIOSpecDefault:
  def spec =
    suite("ChangePersist")(
      test("encodePath percent-encodes a slash and leaves unreserved characters") {
        assertTrue(
          ChangePersist.encodePath("a/b.ts") == "a%2Fb.ts",
          ChangePersist.encodePath("A-z_0.") == "A-z_0.",
          ChangePersist.rel("sess", "turn_1", "a/b.ts", "old") == "sess/turn_1/a%2Fb.ts.old",
        )
      },
      test("index then hydrate round-trips snapshots") {
        val file =
          FileChange(
            "/tmp/a.ts",
            ChangeKind.Modify,
            1,
            1,
            true,
            "c1",
            oldSnapshot = Some("old"),
            newSnapshot = Some("new"),
          )
        val set    = ChangeSet("sess", "turn_1", "edit", List(file), 42L)
        val idx    = ChangePersist.index(List(set))
        val bodies = ChangePersist.bodies(List(set)).toMap
        val out    = ChangePersist.hydrate(idx, bodies.get)
        assertTrue(
          idx.head.files.head.hasOld,
          idx.head.files.head.hasNew,
          ChangePersist.rels(List(set)).toSet == bodies.keySet,
          out == List(set),
        )
      },
      test("hydrate marks missing snapshot files") {
        val file =
          FileChange(
            "/tmp/a.ts",
            ChangeKind.Modify,
            1,
            1,
            true,
            "c1",
            oldSnapshot = Some("old"),
            newSnapshot = Some("new"),
          )
        val set = ChangeSet("sess", "turn_1", "edit", List(file), 42L)
        val out = ChangePersist.hydrate(ChangePersist.index(List(set)), _ => None)
        assertTrue(out.head.files.head.undoDisabled.contains(ChangeSet.MissingSnapshot))
      },
    )
end ChangePersistSpec
