import { expect, it } from "@effect/vitest"
import {
  detectDiffEditor,
  isDiffEditorInput,
  planFollowAlong,
  schemesFromTabInput,
  shouldFollowAlong,
} from "../src/follow-along.ts"
import { ORIGINAL_SCHEME, PROPOSED_SCHEME } from "../src/virtual-docs.ts"

it("does not follow read or search tools even when they report a path", () => {
  expect(shouldFollowAlong("read", { hasDiffs: false })).toBe(false)
  expect(shouldFollowAlong("search")).toBe(false)
  expect(shouldFollowAlong("other")).toBe(false)
  expect(shouldFollowAlong("edit")).toBe(true)
  expect(shouldFollowAlong("other", { hasDiffs: true })).toBe(true)
  expect(shouldFollowAlong("edit", { readOnly: true })).toBe(false)
})

it("preserves focus when the user is in a Beard diff editor", () => {
  const plan = planFollowAlong(
    [{ path: "/tmp/a.ts", line: 4 }],
    { scheme: ORIGINAL_SCHEME },
  )
  expect(plan.preserveFocus).toBe(true)
  expect(plan.reveals[0]).toEqual({ path: "/tmp/a.ts", line: 4 })
})

it("steals focus for follow-along when the user is in a normal editor", () => {
  const plan = planFollowAlong([{ path: "/tmp/a.ts" }], { scheme: "file" })
  expect(plan.preserveFocus).toBe(false)
})

it("preserves focus for a native multi-diff tab", () => {
  const plan = planFollowAlong([{ path: "/tmp/a.ts", line: 1 }], { inDiffEditor: true })
  expect(plan.preserveFocus).toBe(true)
})

it("detects vscode.changes tabs by original/modified or resources", () => {
  expect(isDiffEditorInput({
    original: { scheme: ORIGINAL_SCHEME, path: "/tmp/a.ts" },
    modified: { scheme: PROPOSED_SCHEME, path: "/tmp/a.ts" },
  })).toBe(true)
  expect(isDiffEditorInput({
    resources: [
      { original: { scheme: ORIGINAL_SCHEME }, modified: { scheme: PROPOSED_SCHEME } },
    ],
  })).toBe(true)
  expect(isDiffEditorInput({ uri: { scheme: "file", path: "/tmp/a.ts" } })).toBe(false)
})

it("treats any beard virtual tab in the active group as the diff editor", () => {
  expect(detectDiffEditor({
    scheme: "file",
    tabInput: { uri: { scheme: "file", path: "/tmp/a.ts" } },
    schemesInActiveGroup: ["file", ORIGINAL_SCHEME, PROPOSED_SCHEME],
  })).toBe(true)
})

it("preserves focus when the active editor is missing but a multi-diff tab is selected", () => {
  const input = {
    constructor: { name: "TabInputTextMultiDiff" },
    resources: [
      { original: { scheme: ORIGINAL_SCHEME }, modified: { scheme: PROPOSED_SCHEME } },
    ],
  }
  const plan = planFollowAlong([{ path: "/tmp/a.ts", line: 2 }], { tabInput: input })
  expect(plan.preserveFocus).toBe(true)
  expect(schemesFromTabInput(input)).toEqual([ORIGINAL_SCHEME, PROPOSED_SCHEME])
})
