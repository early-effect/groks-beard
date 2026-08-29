import { expect, it } from "@effect/vitest"
import {
  MISSING_SNAPSHOT_REASON,
  SNAPSHOT_BYTE_CAP,
  SNAPSHOT_FILE_CAP,
  type UndoMutation
} from "@groks-beard/core"
import { ChangeStore, type ChangeStoreFs, snapshotRelPath } from "../src/change-store.ts"

const memory = (now = 1) => {
  const files = new Map<string, string>()
  let index: unknown = []
  const fs: ChangeStoreFs = {
    read: (path) => files.get(path),
    write: (path, text) => {
      files.set(path, text)
    },
    remove: (path) => {
      files.delete(path)
    },
    mkdirp: () => undefined
  }
  const store = new ChangeStore({
    storageRoot: "/store",
    now: () => now,
    join: (...parts) => parts.join("/").replaceAll("//", "/"),
    loadIndex: () => index,
    saveIndex: (next) => {
      index = next
    },
    fs
  })
  return { store, files, index: () => index }
}

const applyPorts = (disk: Record<string, string> = {}) => {
  const applied: Array<UndoMutation> = []
  let confirm = true
  return {
    applied,
    setConfirm: (value: boolean) => {
      confirm = value
    },
    ports: {
      readDisk: (path: string) => disk[path],
      confirmDirty: async () => confirm,
      apply: async (mutations: ReadonlyArray<UndoMutation>) => {
        applied.push(...mutations)
      }
    }
  }
}

it("indexes a tool_call diff in always-approve without a permission card", () => {
  const { store, files } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "fix parser",
    diskIsBefore: true,
    readDisk: () => "old-token\n",
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      status: "pending",
      content: [{
        type: "diff",
        path: "/tmp/a.ts",
        oldText: "old-token",
        newText: "new-token"
      }]
    }
  })
  const listed = store.list()
  expect(listed).toHaveLength(1)
  expect(listed[0]?.title).toBe("fix parser")
  expect(listed[0]?.files[0]?.path).toBe("/tmp/a.ts")
  expect(listed[0]?.files[0]?.snapshotStored).toBe(true)
  const rel = snapshotRelPath("s1", "turn_1", "/tmp/a.ts", "old")
  expect(files.get(`/store/${rel}`)).toContain("old-token")
})

it("Keep drops a path from pending and deletes snapshot files", () => {
  const { store, files } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "edit",
    diskIsBefore: true,
    readDisk: () => undefined,
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      content: [{ type: "diff", path: "/a.ts", oldText: "a", newText: "b" }]
    }
  })
  expect(files.size).toBeGreaterThan(0)
  store.keep("s1", "turn_1", "/a.ts")
  expect(store.list()).toEqual([])
  expect(files.size).toBe(0)
})

it("Undo of modify applies a full-document replace and leaves the file out of pending", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "edit",
    diskIsBefore: true,
    readDisk: () => "old",
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      content: [{ type: "diff", path: "/a.ts", oldText: "old", newText: "new" }]
    }
  })
  const apply = applyPorts({ "/a.ts": "new" })
  const result = await store.undo("s1", "turn_1", "/a.ts", apply.ports)
  expect(result).toEqual({ ok: true })
  expect(apply.applied).toEqual([{ _tag: "replace", path: "/a.ts", text: "old" }])
  expect(store.list()).toEqual([])
})

it("Undo of add deletes the file", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "add",
    diskIsBefore: true,
    readDisk: () => undefined,
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      content: [{ type: "diff", path: "/new.ts", oldText: null, newText: "hello" }]
    }
  })
  const apply = applyPorts({ "/new.ts": "hello" })
  const result = await store.undo("s1", "turn_1", "/new.ts", apply.ports)
  expect(result.ok).toBe(true)
  expect(apply.applied).toEqual([{ _tag: "delete", path: "/new.ts" }])
})

it("Undo of add stays pending when the dirty confirm is cancelled", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "add",
    diskIsBefore: true,
    readDisk: () => undefined,
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      content: [{ type: "diff", path: "/new.ts", oldText: null, newText: "hello" }]
    }
  })
  const apply = applyPorts({ "/new.ts": "dirty" })
  apply.setConfirm(false)
  const result = await store.undo("s1", "turn_1", "/new.ts", apply.ports)
  expect(result).toMatchObject({ ok: false, cancelled: true, path: "/new.ts" })
  expect(store.getFile("s1", "turn_1", "/new.ts")).toBeDefined()
})

it("Undo of delete recreates from the old snapshot", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "delete",
    diskIsBefore: true,
    readDisk: () => "body",
    toolCall: {
      toolCallId: "c1",
      kind: "delete",
      content: [{ type: "diff", path: "/gone.ts", oldText: "body", newText: "" }]
    }
  })
  const apply = applyPorts()
  const result = await store.undo("s1", "turn_1", "/gone.ts", apply.ports)
  expect(result.ok).toBe(true)
  expect(apply.applied).toEqual([{ _tag: "create", path: "/gone.ts", text: "body" }])
})

it("disables Undo of delete when the path exists with different content", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "delete",
    diskIsBefore: true,
    readDisk: () => "body",
    toolCall: {
      toolCallId: "c1",
      kind: "delete",
      content: [{ type: "diff", path: "/gone.ts", oldText: "body", newText: "" }]
    }
  })
  const apply = applyPorts({ "/gone.ts": "someone else wrote this" })
  const result = await store.undo("s1", "turn_1", "/gone.ts", apply.ports)
  expect(result).toEqual({
    ok: false,
    path: "/gone.ts",
    reason: "path exists with different content"
  })
  expect(store.getFile("s1", "turn_1", "/gone.ts")).toBeDefined()
})

it("Undo of move writes the origin and deletes the destination", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "move",
    diskIsBefore: true,
    readDisk: () => "x",
    toolCall: {
      toolCallId: "c1",
      kind: "move",
      rawInput: { path: "/to.ts", from_path: "/from.ts", old_string: "x", new_string: "x" },
      content: [{ type: "diff", path: "/to.ts", oldText: "x", newText: "x" }]
    }
  })
  const apply = applyPorts()
  const result = await store.undo("s1", "turn_1", "/to.ts", apply.ports)
  expect(result.ok).toBe(true)
  expect(apply.applied).toEqual([
    { _tag: "create", path: "/from.ts", text: "x" },
    { _tag: "delete", path: "/to.ts" }
  ])
})

it("disables Undo of move when fromPath is missing", async () => {
  const { store } = memory()
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "move",
    diskIsBefore: true,
    readDisk: () => "x",
    toolCall: {
      toolCallId: "c1",
      kind: "move",
      content: [{ type: "diff", path: "/to.ts", oldText: "x", newText: "x" }]
    }
  })
  const apply = applyPorts()
  const result = await store.undo("s1", "turn_1", "/to.ts", apply.ports)
  expect(result).toEqual({ ok: false, path: "/to.ts", reason: "move target unknown" })
})

it("indexes overflow files without snapshots and disables Undo", () => {
  const { store } = memory()
  for (let i = 0; i < SNAPSHOT_FILE_CAP + 1; i++) {
    store.ingestToolCall({
      sessionId: "s1",
      turnId: "turn_1",
      title: "bulk",
      diskIsBefore: true,
      readDisk: () => undefined,
      toolCall: {
        toolCallId: `c${i}`,
        kind: "edit",
        content: [{ type: "diff", path: `/f${i}.ts`, oldText: "a", newText: "b" }]
      }
    })
  }
  const files = store.getTurn("s1", "turn_1")?.files ?? []
  expect(files).toHaveLength(SNAPSHOT_FILE_CAP + 1)
  const overflow = files.find((file) => file.path === `/f${SNAPSHOT_FILE_CAP}.ts`)
  expect(overflow?.snapshotStored).toBe(false)
  expect(overflow?.undoDisabledReason).toBe(MISSING_SNAPSHOT_REASON)
  expect(store.undoReason()).toBe(MISSING_SNAPSHOT_REASON)
})

it("disables Undo when the byte cap would be exceeded", () => {
  const { store } = memory()
  const huge = "x".repeat(SNAPSHOT_BYTE_CAP)
  store.ingestToolCall({
    sessionId: "s1",
    turnId: "turn_1",
    title: "huge",
    diskIsBefore: true,
    readDisk: () => undefined,
    toolCall: {
      toolCallId: "c1",
      kind: "edit",
      content: [{ type: "diff", path: "/huge.ts", oldText: huge, newText: "y" }]
    }
  })
  const file = store.getFile("s1", "turn_1", "/huge.ts")
  expect(file?.snapshotStored).toBe(false)
  expect(file?.undoDisabledReason).toBe(MISSING_SNAPSHOT_REASON)
})

it("Undo all stops on the first failure and reports the path", async () => {
  const { store } = memory()
  store.ingestReconstructed({
    sessionId: "s1",
    turnId: "turn_1",
    title: "mixed",
    diffs: [
      {
        path: "/ok.ts",
        oldText: "a",
        newText: "b",
        firstChangedLine: 0,
        wholeFile: true,
        kind: "modify",
        toolCallId: "c1"
      },
      {
        path: "/gone.ts",
        oldText: "body",
        newText: "",
        firstChangedLine: 0,
        wholeFile: true,
        kind: "delete",
        toolCallId: "c2"
      }
    ]
  })
  const apply = applyPorts({ "/gone.ts": "different" })
  const result = await store.undoAll("s1", "turn_1", apply.ports)
  expect(result.ok).toBe(false)
  if (!result.ok) expect(result.path).toBe("/gone.ts")
  expect(store.getFile("s1", "turn_1", "/ok.ts")).toBeUndefined()
  expect(store.getFile("s1", "turn_1", "/gone.ts")).toBeDefined()
})
