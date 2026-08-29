import { expect, it } from "@effect/vitest"
import { Schema } from "effect"
import {
  EditorOpenDiffArgs,
  editorSelectionFrom,
  EditorSelectionResult,
  EditorShowChangesArgs,
  preferPendingSelection,
  SELECTION_TEXT_CAP_BYTES,
} from "../src/mcp-tools.ts"
import { chipFromSelection } from "../src/prompt.ts"
import { utf8ByteLength } from "../src/utf8.ts"

it("caps selection text at SELECTION_TEXT_CAP_BYTES and sets truncated", () => {
  const text = "é".repeat(SELECTION_TEXT_CAP_BYTES)
  const result = editorSelectionFrom({
    path: "a.ts",
    absPath: "/repo/a.ts",
    startLine: 1,
    endLine: 2,
    text,
  })
  expect(result.truncated).toBe(true)
  expect(utf8ByteLength(result.text ?? "")).toBeLessThanOrEqual(SELECTION_TEXT_CAP_BYTES)
  expect(result.atRef).toBe("@a.ts:1-2")
})

it("omits atRef when path or range is missing", () => {
  const result = editorSelectionFrom({ path: "a.ts", absPath: "/repo/a.ts" })
  expect(result.atRef).toBeUndefined()
  expect(result.truncated).toBe(false)
})

it("prefers the pending-selection buffer over a live selection", () => {
  const pending = chipFromSelection({
    absPath: "/repo/pending.ts",
    workspaceRoot: "/repo",
    startLine: 3,
    endLine: 9,
  })
  const result = preferPendingSelection(pending, {
    path: "live.ts",
    absPath: "/repo/live.ts",
    startLine: 1,
    endLine: 1,
    text: "live",
  }, "pending-text")
  expect(result.path).toBe("pending.ts")
  expect(result.text).toBe("pending-text")
  expect(result.atRef).toBe("@pending.ts:3-9")
})

it("decodes EditorOpenDiffArgs and EditorShowChangesArgs with optionalKey fields omitted", () => {
  const diff = Schema.decodeUnknownSync(EditorOpenDiffArgs)({ path: "/tmp/a.ts" })
  expect(diff.path).toBe("/tmp/a.ts")
  expect("line" in diff).toBe(false)
  const shown = Schema.decodeUnknownSync(EditorShowChangesArgs)({
    files: [{ path: "/tmp/a.ts", kind: "modify" }],
  })
  expect("title" in shown).toBe(false)
  expect(shown.files).toHaveLength(1)
})

it("round-trips EditorSelectionResult through Schema", () => {
  const encoded = Schema.encodeSync(EditorSelectionResult)(
    editorSelectionFrom({ path: "a.ts", absPath: "/a.ts", startLine: 1, endLine: 1 }),
  )
  expect("text" in encoded).toBe(false)
  const decoded = Schema.decodeUnknownSync(EditorSelectionResult)(encoded)
  expect(decoded.atRef).toBe("@a.ts:1-1")
})
