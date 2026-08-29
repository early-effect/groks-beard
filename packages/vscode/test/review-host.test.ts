import { expect, it } from "@effect/vitest"
import { ChangeStore } from "../src/change-store.ts"
import type { DiffOpenPlan } from "../src/diff-open.ts"
import type { FollowAlongPlan } from "../src/follow-along.ts"
import { ReviewHost } from "../src/review-host.ts"
import { BeardDocStore, ORIGINAL_SCHEME } from "../src/virtual-docs.ts"

const harness = () => {
  const files = new Map<string, string>()
  let index: unknown = []
  const store = new ChangeStore({
    storageRoot: "/store",
    now: () => 1,
    join: (...parts) => parts.join("/"),
    loadIndex: () => index,
    saveIndex: (next) => {
      index = next
    },
    fs: {
      read: (path) => files.get(path),
      write: (path, text) => {
        files.set(path, text)
      },
      remove: (path) => {
        files.delete(path)
      },
      mkdirp: () => undefined,
    },
  })
  const docs = new BeardDocStore()
  const opened: Array<DiffOpenPlan> = []
  const followed: Array<FollowAlongPlan> = []
  const warnings: Array<string> = []
  const disk: Record<string, string> = { "/tmp/a.ts": "old-token\n" }
  const review = new ReviewHost(docs, store, {
    readDisk: (path) => disk[path],
    hasChangesCommand: async () => true,
    openDiffs: async (plan) => {
      opened.push(plan)
    },
    follow: (plan) => {
      followed.push(plan)
    },
    warn: (message) => {
      warnings.push(message)
    },
    activeScheme: () => ORIGINAL_SCHEME,
    inDiffEditor: () => true,
  })
  return { review, store, docs, opened, followed, warnings, disk }
}

it("opens a reconstructed multi-diff without resolving permission", async () => {
  const { review, opened, docs } = harness()
  review.setTurn("sess", "turn_1", "fix it")
  review.rememberPermission("perm-1", {
    sessionId: "sess",
    toolCall: {
      toolCallId: "call_1",
      kind: "edit",
      content: [{
        type: "diff",
        path: "/tmp/a.ts",
        oldText: "old-token",
        newText: "new-token",
      }],
    },
    options: [{ optionId: "allow-once", kind: "allow_once" }],
  })
  await review.openPermissionDiff("perm-1")
  expect(opened[0]?.mode).toBe("multi")
  expect(docs.get(ORIGINAL_SCHEME, "/tmp/a.ts")).toBe("old-token\n")
})

it("falls back to pairwise diff when vscode.changes is missing", async () => {
  const { store, docs } = harness()
  const opened: Array<DiffOpenPlan> = []
  const review = new ReviewHost(docs, store, {
    readDisk: () => "old\n",
    hasChangesCommand: async () => false,
    openDiffs: async (plan) => {
      opened.push(plan)
    },
    follow: () => undefined,
    warn: () => undefined,
    activeScheme: () => undefined,
    inDiffEditor: () => false,
  })
  review.setTurn("sess", "turn_1", "edit")
  review.rememberPermission("perm-1", {
    sessionId: "sess",
    toolCall: {
      toolCallId: "c",
      content: [{ type: "diff", path: "/a.ts", oldText: "old", newText: "new" }],
    },
  })
  await review.openPermissionDiff("perm-1")
  expect(opened[0]?.mode).toBe("pairwise")
})

it("fills Changes from a completed always-approve tool_call", () => {
  const { review, store, followed } = harness()
  review.setTurn("sess", "turn_1", "yolo")
  review.ingestUpdate({
    sessionId: "sess",
    update: {
      sessionUpdate: "tool_call",
      toolCallId: "c1",
      kind: "edit",
      status: "completed",
      locations: [{ path: "/tmp/a.ts", line: 2 }],
      content: [{
        type: "diff",
        path: "/tmp/a.ts",
        oldText: "old-token",
        newText: "new-token",
      }],
    },
  }, { sessionId: "sess", turnId: "turn_1", title: "yolo" })
  expect(store.getFile("sess", "turn_1", "/tmp/a.ts")?.kind).toBe("modify")
  expect(followed[0]?.preserveFocus).toBe(true)
  expect(followed[0]?.reveals[0]?.line).toBe(2)
})

it("drops pending files when the turn is cancelled", () => {
  const { review, store } = harness()
  review.setTurn("sess", "turn_1", "edit")
  review.rememberPermission("perm-1", {
    sessionId: "sess",
    toolCall: {
      toolCallId: "call_1",
      content: [{ type: "diff", path: "/tmp/a.ts", oldText: "old-token", newText: "new-token" }],
    },
    options: [{ optionId: "allow-once", kind: "allow_once" }],
  })
  review.cancelPendingPermissions()
  expect(store.getFile("sess", "turn_1", "/tmp/a.ts")).toBeUndefined()
})

it("keeps Allowed files in Changes when a later cancel arrives", () => {
  const { review, store } = harness()
  review.setTurn("sess", "turn_1", "edit")
  review.rememberPermission("perm-1", {
    sessionId: "sess",
    toolCall: {
      toolCallId: "call_1",
      content: [{ type: "diff", path: "/tmp/a.ts", oldText: "old-token", newText: "new-token" }],
    },
    options: [{ optionId: "allow-once", kind: "allow_once" }],
  })
  review.onPermissionChoice("perm-1", "allow-once")
  review.cancelPendingPermissions()
  expect(store.getFile("sess", "turn_1", "/tmp/a.ts")).toBeDefined()
})

it("drops pending files when the permission is rejected", () => {
  const { review, store } = harness()
  review.setTurn("sess", "turn_1", "edit")
  review.rememberPermission("perm-1", {
    sessionId: "sess",
    toolCall: {
      toolCallId: "call_1",
      content: [{ type: "diff", path: "/tmp/a.ts", oldText: "old-token", newText: "new-token" }],
    },
    options: [{ optionId: "reject-once", kind: "reject_once" }],
  })
  expect(store.getFile("sess", "turn_1", "/tmp/a.ts")).toBeDefined()
  review.onPermissionChoice("perm-1", "reject-once")
  expect(store.getFile("sess", "turn_1", "/tmp/a.ts")).toBeUndefined()
})
