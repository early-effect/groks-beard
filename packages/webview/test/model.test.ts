import { expect, it } from "@effect/vitest"
import { HOST_MSG_TAGS } from "@groks-beard/core"
import { applyHostMsg, emptyChatModel, HOST_MSG_APPLIED, turnIsRunning } from "../src/model.ts"
import { thoughtBlock, thoughtSummaryLabel } from "../src/thinking.ts"

it("applyHostMsg handles every closed HostMsg tag", () => {
  expect(Object.keys(HOST_MSG_APPLIED).sort()).toEqual([...HOST_MSG_TAGS].sort())
})

it("keeps the original tool title when a later update only sets status", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "toolGroup",
    turnId: "t",
    tools: [{
      id: "c1",
      title: "search_tool",
      kind: "other",
      status: "pending",
      input: '{"query":"metals"}',
    }],
  })
  model = applyHostMsg(model, {
    _tag: "toolGroup",
    turnId: "t",
    tools: [{ id: "c1", title: "", kind: "", status: "completed", output: "17 tools" }],
  })
  expect(model.turns[0]?.tools[0]).toMatchObject({
    id: "c1",
    title: "search_tool",
    kind: "other",
    status: "completed",
    input: '{"query":"metals"}',
    output: "17 tools",
  })
})

it("treats a turn without stopReason as running", () => {
  let model = emptyChatModel()
  expect(turnIsRunning(model)).toBe(false)
  model = applyHostMsg(model, {
    _tag: "userMessage",
    turnId: "t",
    text: "go",
    chips: [],
  })
  expect(turnIsRunning(model)).toBe(true)
  model = applyHostMsg(model, { _tag: "turnEnd", turnId: "t", stopReason: "cancelled" })
  expect(turnIsRunning(model)).toBe(false)
})

it("concatenates thought chunks into one expandable stream per turn", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "userMessage",
    turnId: "turn_1",
    text: "explain this",
    chips: [],
  })
  model = applyHostMsg(model, {
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Considering the selection.\n",
  })
  model = applyHostMsg(model, {
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Then I'll answer.\n",
  })
  model = applyHostMsg(model, {
    _tag: "agentChunk",
    turnId: "turn_1",
    text: "hello",
  })
  const turn = model.turns[0]
  expect(turn?.thought).toBe("Considering the selection.\nThen I'll answer.\n")
  expect(turn?.agent).toBe("hello")
  const block = thoughtBlock(turn?.thought ?? "", false, false)
  expect(block?.open).toBe(false)
  expect(block?.stream).toBe("Considering the selection.\nThen I'll answer.\n")
  expect(block?.summary.startsWith("Thinking:")).toBe(true)
})

it("keeps the full thought stream after the turn ends", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, { _tag: "thoughtChunk", turnId: "t", text: "step one\nstep two\n" })
  model = applyHostMsg(model, { _tag: "turnEnd", turnId: "t", stopReason: "end_turn" })
  const turn = model.turns[0]
  expect(thoughtSummaryLabel(turn?.thought ?? "", true).startsWith("Thought:")).toBe(true)
  expect(thoughtBlock(turn?.thought ?? "", true, true)?.stream).toContain("step two")
})

it("does not build a thought block when the stream is empty", () => {
  expect(thoughtBlock("", false, false)).toBeUndefined()
})

it("stores settingsState for the gear panel", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "settingsState",
    cliPath: "/usr/bin/grok",
    nodePath: "",
    includeActiveFileByDefault: true,
    useCtrlEnterToSend: false,
    changesPresentation: "toast",
  })
  expect(model.settings?.cliPath).toBe("/usr/bin/grok")
  expect(model.settings?.changesPresentation).toBe("toast")
})

it("stores the live editor file and selection", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "editorContext",
    path: "src/Main.scala",
    startLine: 10,
    startCol: 2,
    endLine: 12,
    endCol: 4,
    hasSelection: true,
  })
  expect(model.editor).toMatchObject({
    path: "src/Main.scala",
    startLine: 10,
    hasSelection: true,
  })
})

it("replaces mention results so a blank query can close the list", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "mentionResults",
    query: "Main",
    files: [{ path: "src/Main.scala", absPath: "/repo/src/Main.scala" }],
  })
  expect(model.mentionFiles).toHaveLength(1)
  model = applyHostMsg(model, { _tag: "mentionResults", query: "", files: [] })
  expect(model.mentionQuery).toBe("")
  expect(model.mentionFiles).toEqual([])
})

it("stores an mcpCatalog for the tools chip", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "mcpCatalog",
    loading: false,
    healthyCount: 1,
    failingCount: 0,
    servers: [{
      name: "metals",
      transport: "stdio",
      source: "~/.grok/config.toml",
      healthy: true,
      checks: [{ label: "12 tools discovered", passed: true }],
      tools: [
        { name: "compile-file", enabled: true },
        { name: "test", enabled: false },
      ],
    }],
  })
  expect(model.mcp?.servers[0]?.name).toBe("metals")
  expect(model.mcp?.healthyCount).toBe(1)
  expect(model.mcp?.servers[0]?.tools).toEqual([
    { name: "compile-file", enabled: true },
    { name: "test", enabled: false },
  ])
})

it("shows and hides the pending-changes toast from changesSummary", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "changesSummary",
    fileCount: 2,
    additions: 4,
    deletions: 1,
  })
  expect(model.changes).toEqual({ fileCount: 2, additions: 4, deletions: 1 })
  model = applyHostMsg(model, {
    _tag: "changesSummary",
    fileCount: 0,
    additions: 0,
    deletions: 0,
  })
  expect(model.changes).toBeUndefined()
})

it("keeps mode and model lists across occupancy-only sessionMeta", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "sessionMeta",
    sessionId: "s",
    title: "",
    modeId: "plan",
    modelId: "grok-4.6",
    availableModes: [{ id: "plan", name: "Plan" }],
    availableModels: [{
      modelId: "grok-4.6",
      name: "Grok 4.6",
      reasoning: {
        current: "high",
        options: [{ value: "high", name: "High" }],
      },
    }],
  })
  model = applyHostMsg(model, {
    _tag: "sessionMeta",
    sessionId: "s",
    title: "",
    modeId: "",
    occupancy: { used: 12, size: 256 },
  })
  expect(model.session?.modeId).toBe("plan")
  expect(model.session?.modelId).toBe("grok-4.6")
  expect(model.session?.occupancy).toEqual({ used: 12, size: 256 })
  expect(model.session?.availableModes).toEqual([{ id: "plan", name: "Plan" }])
  expect(model.session?.availableModels?.[0]?.name).toBe("Grok 4.6")
  expect(model.session?.availableModels?.[0]?.reasoning?.current).toBe("high")
})
