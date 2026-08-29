import { expect, it } from "@effect/vitest"
import {
  canStoreSnapshot,
  diffsFromRawInput,
  fileChangeFromReconstructed,
  inferChangeKind,
  MISSING_SNAPSHOT_REASON,
  permissionOptionKind,
  reconstructToolDiffs,
  SNAPSHOT_BYTE_CAP,
  SNAPSHOT_FILE_CAP,
  snapshotBytesFor,
  toolCallFromPermissionParams,
  undoPlan,
} from "../src/index.ts"

it("reconstructs a permission-time region against disk", () => {
  const disk = "aaa\nold-token\nccc\n"
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "call_1",
      kind: "edit",
      content: [{
        type: "diff",
        path: "/tmp/file.ts",
        oldText: "old-token",
        newText: "new-token",
      }],
    },
    diskText: (path) => path === "/tmp/file.ts" ? disk : undefined,
    diskIsBefore: true,
  })
  expect(diffs).toHaveLength(1)
  expect(diffs[0]?.wholeFile).toBe(true)
  expect(diffs[0]?.oldText).toBe(disk)
  expect(diffs[0]?.newText).toBe("aaa\nnew-token\nccc\n")
  expect(diffs[0]?.kind).toBe("modify")
})

it("falls back to rawInput old_string/new_string/path", () => {
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "call_2",
      kind: "edit",
      rawInput: {
        path: "/tmp/a.ts",
        old_string: "foo",
        new_string: "bar",
      },
    },
    diskText: () => "foo\n",
    diskIsBefore: true,
  })
  expect(diffs[0]?.path).toBe("/tmp/a.ts")
  expect(diffs[0]?.newText).toBe("bar\n")
})

it("treats null oldText as an add when the file is missing", () => {
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "call_3",
      kind: "edit",
      content: [{ type: "diff", path: "/tmp/new.ts", oldText: null, newText: "hello" }],
    },
    diskText: () => undefined,
    diskIsBefore: true,
  })
  expect(diffs[0]?.kind).toBe("add")
  expect(diffs[0]?.oldText).toBe("")
  expect(diffs[0]?.newText).toBe("hello")
})

it("treats null oldText plus existing disk as a modify overwrite", () => {
  const disk = "keep me\nplease\n"
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "call_write",
      kind: "edit",
      content: [{ type: "diff", path: "/tmp/file.ts", oldText: null, newText: "replaced" }],
    },
    diskText: () => disk,
    diskIsBefore: true,
  })
  expect(diffs[0]?.kind).toBe("modify")
  expect(diffs[0]?.oldText).toBe(disk)
  expect(diffs[0]?.newText).toBe("replaced")
  const change = fileChangeFromReconstructed(diffs[0]!)
  expect(change.oldSnapshot).toBe(disk)
  expect(change.kind).toBe("modify")
})

it("uses toolKind delete, not empty newText, as the delete signal", () => {
  expect(inferChangeKind({
    toolKind: "edit",
    oldText: "gone",
    newText: "",
  })).toBe("modify")
  expect(inferChangeKind({
    toolKind: "delete",
    oldText: "gone",
    newText: "",
  })).toBe("delete")
})

it("classifies a post-write delete as modify when the path still exists", () => {
  expect(inferChangeKind({
    toolKind: "delete",
    oldText: "body",
    newText: "",
    diskExists: true,
    diskIsBefore: false,
  })).toBe("modify")
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "del",
      kind: "delete",
      status: "completed",
      content: [{ type: "diff", path: "/gone.ts", oldText: "body", newText: "" }],
    },
    diskText: () => "",
    diskIsBefore: false,
  })
  expect(diffs[0]?.kind).toBe("modify")
  expect(diffs[0]?.wholeFile).toBe(true)
  expect(diffs[0]?.oldText).toBe("body")
  expect(diffs[0]?.newText).toBe("")
  const plan = undoPlan(fileChangeFromReconstructed(diffs[0]!))
  expect(plan).toEqual({ _tag: "replace", path: "/gone.ts", text: "body" })
})

it("marks a completed missing-disk delete as a region stand-in", () => {
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "del",
      kind: "delete",
      status: "completed",
      content: [{ type: "diff", path: "/gone.ts", oldText: "body", newText: "" }],
    },
    diskText: () => undefined,
    diskIsBefore: false,
  })
  expect(diffs[0]?.wholeFile).toBe(true)
  expect(diffs[0]?.regionStandIn).toBe(true)
  expect(diffs[0]?.oldText).toBe("body")
})

it("treats a completed delete with missing disk as a whole-file delete", () => {
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "del",
      kind: "delete",
      status: "completed",
      content: [{ type: "diff", path: "/gone.ts", oldText: "body", newText: "" }],
    },
    diskText: () => undefined,
    diskIsBefore: false,
  })
  expect(diffs[0]?.kind).toBe("delete")
  expect(diffs[0]?.wholeFile).toBe(true)
  expect(undoPlan(fileChangeFromReconstructed(diffs[0]!))).toEqual({
    _tag: "create",
    path: "/gone.ts",
    text: "body",
  })
})

it("recovers original from a completed post-write update", () => {
  const diffs = reconstructToolDiffs({
    toolCall: {
      toolCallId: "call_4",
      kind: "edit",
      status: "completed",
      content: [{
        type: "diff",
        path: "/tmp/file.ts",
        oldText: "old-token",
        newText: "new-token",
      }],
    },
    diskText: () => "aaa\nnew-token\nccc\n",
    diskIsBefore: false,
  })
  expect(diffs[0]?.oldText).toBe("aaa\nold-token\nccc\n")
  expect(diffs[0]?.newText).toBe("aaa\nnew-token\nccc\n")
})

it("reads the toolCall nested in permission params", () => {
  const nested = toolCallFromPermissionParams({
    sessionId: "s",
    toolCall: {
      toolCallId: "c",
      content: [{ type: "diff", path: "/a", oldText: "a", newText: "b" }],
    },
  })
  const diffs = reconstructToolDiffs({
    toolCall: nested,
    diskText: () => undefined,
    diskIsBefore: true,
  })
  expect(diffs[0]?.path).toBe("/a")
})

it("identifies reject option kinds", () => {
  expect(permissionOptionKind({
    options: [{ optionId: "reject-once", kind: "reject_once" }],
  }, "reject-once")).toBe("reject_once")
})

it("builds a FileChange with snapshots and line stats", () => {
  const [diff] = reconstructToolDiffs({
    toolCall: {
      toolCallId: "c",
      kind: "edit",
      content: [{ type: "diff", path: "/a.ts", oldText: "a\n", newText: "b\n" }],
    },
    diskText: () => undefined,
    diskIsBefore: true,
  })
  expect(diff).toBeDefined()
  const change = fileChangeFromReconstructed(diff!)
  expect(change.additions).toBeGreaterThanOrEqual(1)
  expect(change.oldSnapshot).toBe("a\n")
  expect(snapshotBytesFor(change)).toBeGreaterThan(0)
})

it("parses rawInput without content diffs", () => {
  expect(diffsFromRawInput({
    path: "/x.ts",
    old_string: "a",
    new_string: "b",
    replace_all: true,
  })).toEqual([{ path: "/x.ts", oldText: "a", newText: "b", oldTextWasNull: false }])
})

it("rejects a snapshot that would exceed the pending cap", () => {
  expect(canStoreSnapshot(SNAPSHOT_FILE_CAP, 0, 10)).toBe(false)
  expect(canStoreSnapshot(0, SNAPSHOT_BYTE_CAP, 1)).toBe(false)
  expect(canStoreSnapshot(0, 0, 10)).toBe(true)
  expect(MISSING_SNAPSHOT_REASON).toBe("missing snapshot")
})
