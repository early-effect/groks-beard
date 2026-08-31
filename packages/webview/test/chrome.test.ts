import { expect, it } from "@effect/vitest"
import {
  addSelectionShortcut,
  clipText,
  compactTokens,
  editorCaretLabel,
  editorContextKind,
  editorContextLabel,
  editorSelectionRange,
  effortLabel,
  mcpNeedsFolderTrust,
  mcpToolSummary,
  modeLabel,
  modelChipLabel,
  modelLabel,
  modeTip,
  occupancyLabel,
  occupancyTone,
  permissionTip,
  reasoningChoicesFor,
  selectionAlreadyChipped,
  sendShortcut,
  splitToolTail,
  titleFromId,
  TOOL_TAIL,
  toolRollupLabel,
} from "../src/chrome.ts"

it("maps known mode ids to display names", () => {
  expect(modeLabel("plan")).toBe("Plan")
  expect(modeLabel("always-approve")).toBe("Always approve")
  expect(modeLabel("auto")).toBe("Auto")
  expect(modeLabel("normal", [{ id: "normal", name: "Ask" }])).toBe("Ask")
})

it("explains modes and permission options for tooltips", () => {
  expect(modeTip("plan")).toContain("plan")
  expect(modeTip("always-approve")).toContain("Skip permission")
  expect(permissionTip({ name: "Allow once", kind: "allow_once" })).toContain("once")
  expect(permissionTip({ name: "Reject", kind: "reject_always" })).toContain("deny")
  expect(addSelectionShortcut("MacIntel")).toBe("⌘⇧;")
  expect(addSelectionShortcut("Win32")).toBe("Ctrl+Shift+;")
  expect(sendShortcut(false, "MacIntel")).toBe("Enter")
  expect(sendShortcut(true, "MacIntel")).toBe("⌘↩")
})

it("title-cases unknown ids", () => {
  expect(titleFromId("edit-only")).toBe("Edit Only")
})

it("formats the live editor caret and selection", () => {
  const caret = {
    path: "src/Main.scala",
    startLine: 12,
    startCol: 1,
    endLine: 12,
    endCol: 1,
    hasSelection: false,
    hasRange: false,
  }
  expect(editorContextKind(caret)).toBe("File")
  expect(editorContextLabel(caret)).toBe("src/Main.scala:12:1")
  const range = { ...caret, endLine: 40, endCol: 8, hasSelection: true, hasRange: true }
  expect(editorContextKind(range)).toBe("Selection")
  expect(editorContextLabel(range)).toBe("src/Main.scala:12:1-40:8")
  expect(editorContextLabel({ ...caret, endCol: 9, hasSelection: true })).toBe(
    "src/Main.scala:12:1-9",
  )
  expect(editorCaretLabel(caret)).toBe("12:1")
  expect(editorSelectionRange(range)).toBe("12:1-40:8")
  expect(selectionAlreadyChipped(range, [])).toBe(false)
  expect(selectionAlreadyChipped(range, [{
    path: "src/Main.scala",
    startLine: 12,
    endLine: 40,
  }])).toBe(true)
  expect(selectionAlreadyChipped(range, [{
    path: "src/Main.scala",
    startLine: 1,
    endLine: 2,
  }])).toBe(false)
})

it("summarizes MCP tool enablement with all on as the default", () => {
  expect(mcpToolSummary([{ enabled: true }, { enabled: true }])).toBe("2 tools · all on")
  expect(mcpToolSummary([{ enabled: true }, { enabled: false }])).toBe("1 of 2 tools on")
})

it("flags repo-local MCP blocked by folder trust", () => {
  expect(mcpNeedsFolderTrust([{
    checks: [{
      label: "folder untrusted",
      passed: false,
    }],
  }])).toBe(true)
  expect(mcpNeedsFolderTrust([{
    checks: [{ label: "handshake OK", passed: true }],
  }])).toBe(false)
})

it("rolls long tool lists up to a visible tail", () => {
  expect(TOOL_TAIL).toBe(4)
  expect(splitToolTail(["a", "b", "c", "d"])).toEqual({
    earlier: [],
    visible: ["a", "b", "c", "d"],
  })
  expect(splitToolTail(["a", "b", "c", "d", "e", "f"])).toEqual({
    earlier: ["a", "b"],
    visible: ["c", "d", "e", "f"],
  })
  expect(toolRollupLabel(1)).toBe("1 earlier tool")
  expect(toolRollupLabel(12)).toBe("12 earlier tools")
})

it("clips long tool payloads and marks them as clipped", () => {
  expect(clipText("short").clipped).toBe(false)
  const long = "x".repeat(2000)
  expect(clipText(long, 1600).clipped).toBe(true)
  expect(clipText(long, 1600).shown.length).toBeLessThan(long.length)
})

it("formats occupancy against the model context window", () => {
  expect(compactTokens(500000)).toBe("500k")
  expect(occupancyLabel(12500, 500000)).toBe("12.5k / 500k · 3%")
  expect(occupancyTone(430000, 500000)).toBe("hot")
  expect(occupancyTone(360000, 500000)).toBe("warn")
  expect(occupancyTone(1000, 500000)).toBe("ok")
})

it("prefers the advertised model name over the raw id", () => {
  expect(modelLabel("grok-4.6", [
    { modelId: "grok-4.5", name: "Grok 4.5" },
    { modelId: "grok-4.6", name: "Grok 4.6" },
  ])).toBe("Grok 4.6")
  expect(modelLabel("grok-code", [])).toBe("grok-code")
  expect(modelLabel(undefined, [{ modelId: "grok-4.6", name: "Grok 4.6" }])).toBe("Grok 4.6")
})

it("puts effort on the model chip only when that model advertises it", () => {
  const grok = {
    modelId: "grok-4.6",
    name: "Grok 4.6",
    reasoning: {
      current: "high",
      options: [
        { value: "high", name: "High Effort" },
        { value: "xhigh", name: "Extra High Effort" },
      ],
    },
  }
  const qwen = { modelId: "vertigo-qwen", name: "Qwen 3.8 27B" }
  expect(effortLabel(grok.reasoning.options[0]!)).toBe("High")
  expect(
    modelChipLabel("grok-4.6", [grok, qwen], { current: "xhigh", options: grok.reasoning.options }),
  )
    .toBe("Grok 4.6 · Extra High")
  expect(modelChipLabel("vertigo-qwen", [grok, qwen], {
    current: "high",
    options: grok.reasoning.options,
  })).toBe("Qwen 3.8 27B")
  expect(reasoningChoicesFor(qwen).options.map((item) => item.value)).toEqual([
    "low",
    "medium",
    "high",
    "xhigh",
  ])
})
