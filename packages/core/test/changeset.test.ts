import { expect, it } from "@effect/vitest"
import * as fc from "effect/testing/FastCheck"
import {
  applyUndoToSnapshots,
  ChangeSet,
  FileChange,
  keepFile,
  lineDiffStats,
  resolveUndo,
  turnTitleFromPrompt,
  undoPlan,
} from "../src/changeset.ts"

const change = (fields: ConstructorParameters<typeof FileChange>[0]) => new FileChange(fields)

it("Keep drops a path from the pending set", () => {
  const set = new ChangeSet({
    sessionId: "s",
    turnId: "t",
    title: "edit",
    createdAt: 1,
    files: [
      change({
        path: "/a.ts",
        kind: "modify",
        additions: 1,
        deletions: 1,
        wholeFile: true,
        toolCallId: "c1",
        oldSnapshot: "a",
        newSnapshot: "b",
      }),
      change({
        path: "/b.ts",
        kind: "add",
        additions: 1,
        deletions: 0,
        wholeFile: true,
        toolCallId: "c2",
        newSnapshot: "n",
      }),
    ],
  })
  expect(keepFile(set, "/a.ts").files.map((file) => file.path)).toEqual(["/b.ts"])
})

it("Undo of modify replaces with the old snapshot", () => {
  const plan = undoPlan(
    change({
      path: "/a.ts",
      kind: "modify",
      additions: 1,
      deletions: 1,
      wholeFile: true,
      toolCallId: "c",
      oldSnapshot: "old",
      newSnapshot: "new",
    }),
  )
  expect(plan).toEqual({ _tag: "replace", path: "/a.ts", text: "old" })
})

it("Undo of delete recreates the file", () => {
  const plan = undoPlan(
    change({
      path: "/gone.ts",
      kind: "delete",
      additions: 0,
      deletions: 3,
      wholeFile: true,
      toolCallId: "c",
      oldSnapshot: "body",
    }),
  )
  expect(plan).toEqual({ _tag: "create", path: "/gone.ts", text: "body" })
})

it("Undo of add deletes the file", () => {
  const plan = undoPlan(
    change({
      path: "/new.ts",
      kind: "add",
      additions: 2,
      deletions: 0,
      wholeFile: true,
      toolCallId: "c",
      newSnapshot: "hi",
    }),
    "hi",
  )
  expect(plan).toEqual({ _tag: "delete", path: "/new.ts", confirmIfDirty: false })
})

it("Undo of move reverses when both paths are known", () => {
  const plan = undoPlan(
    change({
      path: "/to.ts",
      kind: "move",
      fromPath: "/from.ts",
      additions: 0,
      deletions: 0,
      wholeFile: true,
      toolCallId: "c",
      oldSnapshot: "x",
    }),
  )
  expect(plan._tag).toBe("moveReverse")
})

it("disables Undo of move without fromPath", () => {
  const plan = undoPlan(
    change({
      path: "/to.ts",
      kind: "move",
      additions: 0,
      deletions: 0,
      wholeFile: true,
      toolCallId: "c",
      oldSnapshot: "x",
    }),
  )
  expect(plan).toEqual({ _tag: "disabled", reason: "move target unknown" })
})

it("counts line additions and deletions", () => {
  expect(lineDiffStats("a\nb\n", "a\nc\n")).toEqual({ additions: 1, deletions: 1 })
})

it("titles a turn from the first non-empty prompt line", () => {
  expect(turnTitleFromPrompt("\n  Fix the parser  \nmore")).toBe("Fix the parser")
})

it("resolveUndo of add confirms when the buffer is dirty", async () => {
  const file = change({
    path: "/new.ts",
    kind: "add",
    additions: 1,
    deletions: 0,
    wholeFile: true,
    toolCallId: "c",
    newSnapshot: "hi",
  })
  const cancelled = await resolveUndo(file, "dirty", async () => false)
  expect(cancelled._tag).toBe("cancelled")
  const applied = await resolveUndo(file, "hi", async () => {
    throw new Error("should not confirm")
  })
  expect(applied).toEqual({ _tag: "apply", mutations: [{ _tag: "delete", path: "/new.ts" }] })
})

it("Undo of a known modify snapshot returns the original bytes", () => {
  fc.assert(
    fc.property(fc.string(), fc.string(), (oldText, newText) => {
      const file = change({
        path: "/f.ts",
        kind: "modify",
        additions: 1,
        deletions: 1,
        wholeFile: true,
        toolCallId: "c",
        oldSnapshot: oldText,
        newSnapshot: newText,
      })
      return applyUndoToSnapshots(file) === oldText
    }),
  )
})
