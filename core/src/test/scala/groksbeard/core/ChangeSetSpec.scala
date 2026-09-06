package groksbeard.core

import zio.test.*
import zio.test.Gen

object ChangeSetSpec extends ZIOSpecDefault:
  def spec =
    suite("ChangeSet")(
      test("Keep drops a path from the pending set") {
        val set  = ChangeSet("s", "t", "edit", List(modify("/a.ts"), add("/b.ts")), 1)
        val next = ChangeSet.keepFile(set, "/a.ts")
        assertTrue(next.files.map(_.path) == List("/b.ts"))
      },
      test("Undo of modify replaces with the old snapshot") {
        assertTrue(
          ChangeSet.undoPlan(modify("/a.ts")) == UndoPlan.Replace("/a.ts", "old")
        )
      },
      test("Undo of delete recreates the file") {
        val file = FileChange("/gone.ts", ChangeKind.Delete, 0, 3, true, "c", oldSnapshot = Some("body"))
        assertTrue(ChangeSet.undoPlan(file) == UndoPlan.Create("/gone.ts", "body"))
      },
      test("Undo of add deletes the file") {
        val file = FileChange("/new.ts", ChangeKind.Add, 2, 0, true, "c", newSnapshot = Some("hi"))
        assertTrue(ChangeSet.undoPlan(file, Some("hi")) == UndoPlan.Delete("/new.ts", confirmIfDirty = false))
      },
      test("Undo of move reverses when both paths are known") {
        val file =
          FileChange("/to.ts", ChangeKind.Move, 0, 0, true, "c", fromPath = Some("/from.ts"), oldSnapshot = Some("x"))
        assertTrue(
          ChangeSet.undoPlan(file) == UndoPlan.MoveReverse("/from.ts", "/to.ts", "x")
        )
      },
      test("disables Undo when a modify is region-only") {
        val file = modify("/huge.ts").copy(wholeFile = false, oldSnapshot = Some("old-region"))
        assertTrue(ChangeSet.undoPlan(file) == UndoPlan.Disabled(ChangeSet.RegionOnly))
      },
      test("disables Undo of move without fromPath") {
        val file = FileChange("/to.ts", ChangeKind.Move, 0, 0, true, "c", oldSnapshot = Some("x"))
        assertTrue(ChangeSet.undoPlan(file) == UndoPlan.Disabled(ChangeSet.MoveTargetUnknown))
      },
      test("still recreates a delete when the snapshot is the ACP old body") {
        val file = FileChange("/gone.ts", ChangeKind.Delete, 0, 1, false, "c", oldSnapshot = Some("body"))
        assertTrue(ChangeSet.undoPlan(file) == UndoPlan.Create("/gone.ts", "body"))
      },
      test("keeps a stored whole-file row over a completed region stand-in") {
        assertTrue(
          ChangeSet.shouldKeepExisting(true, true, incomingWholeFile = true, regionStandIn = true),
          ChangeSet.shouldKeepExisting(true, true, incomingWholeFile = false, regionStandIn = false),
          !ChangeSet.shouldKeepExisting(true, true, incomingWholeFile = true, regionStandIn = false),
        )
      },
      test("counts line additions and deletions") {
        assertTrue(ChangeSet.lineDiffStats("a\nb\n", "a\nc\n") == (1, 1))
      },
      test("identical files have zero line stats") {
        assertTrue(ChangeSet.lineDiffStats("a\nb", "a\nb") == (0, 0))
      },
      test("insert-only and delete-only line stats") {
        assertTrue(
          ChangeSet.lineDiffStats("a\n", "a\nb\n") == (1, 0),
          ChangeSet.lineDiffStats("a\nb\n", "a\n") == (0, 1),
        )
      },
      test("titles a turn from the first non-empty prompt line") {
        assertTrue(ChangeSet.turnTitle("\n  Fix the parser  \nmore") == "Fix the parser")
      },
      test("truncates long turn titles") {
        val long = "x" * 90
        assertTrue(ChangeSet.turnTitle(long) == s"${"x" * 77}...")
      },
      test("resolveUndo of add confirms when the buffer is dirty") {
        val file = FileChange("/new.ts", ChangeKind.Add, 1, 0, true, "c", newSnapshot = Some("hi"))
        assertTrue(
          ChangeSet.resolveUndo(file, Some("dirty"), confirmDirty = false) == UndoResolution.Cancelled,
          ChangeSet.resolveUndo(file, Some("hi"), confirmDirty = true) ==
            UndoResolution.Apply(List(UndoMutation.Delete("/new.ts"))),
        )
      },
      test("Undo of a known modify snapshot returns the original bytes") {
        check(Gen.stringBounded(0, 40)(Gen.alphaNumericChar), Gen.stringBounded(0, 40)(Gen.alphaNumericChar)) {
          (oldText, newText) =>
            val file = modify("/f.ts").copy(oldSnapshot = Some(oldText), newSnapshot = Some(newText))
            assertTrue(ChangeSet.applyUndoToSnapshots(file).contains(oldText))
        }
      },
      test("ChangeStore keep empties the summary") {
        val store = ChangeStore()
        store.ingest("s", "t", "edit", List(modify("/a.ts")))
        store.keep("/a.ts")
        assertTrue(store.summary.fileCount == 0, store.pending.isEmpty)
      },
      test("keepTurn drops only that turn") {
        val store = ChangeStore()
        store.ingest("s", "t1", "one", List(modify("/a.ts")))
        store.ingest("s", "t2", "two", List(modify("/b.ts")))
        store.keepTurn("t1")
        assertTrue(store.pending.map(_.path) == List("/b.ts"), store.summary.files.head.turnTitle == "two")
      },
      test("keepAll drops every pending file") {
        val store = ChangeStore()
        store.ingest("s", "t1", "one", List(modify("/a.ts")))
        store.ingest("s", "t2", "two", List(modify("/b.ts")))
        store.keepAll()
        assertTrue(store.pending.isEmpty, store.summary.fileCount == 0)
      },
      test("groupByTurn preserves first-seen order") {
        val files = List(
          ChangeSet.toView(modify("/a.ts"), "t1", "one"),
          ChangeSet.toView(modify("/b.ts"), "t2", "two"),
          ChangeSet.toView(modify("/c.ts"), "t1", "one"),
        )
        val groups = ChangeSet.groupByTurn(files)
        assertTrue(
          groups.map(_._1) == List("t1", "t2"),
          groups.head._3.map(_.path) == List("/a.ts", "/c.ts"),
        )
      },
    )

  private def modify(path: String): FileChange =
    FileChange(path, ChangeKind.Modify, 1, 1, true, "c1", oldSnapshot = Some("old"), newSnapshot = Some("new"))

  private def add(path: String): FileChange =
    FileChange(path, ChangeKind.Add, 1, 0, true, "c2", newSnapshot = Some("n"))
end ChangeSetSpec
