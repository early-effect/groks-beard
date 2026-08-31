import { expect, it } from "@effect/vitest"
import {
  decodeHostMsg,
  decodeWebviewMsg,
  displayStopReason,
  HOST_MSG_HANDLED,
  HOST_MSG_TAGS,
  WEBVIEW_MSG_HANDLED,
  WEBVIEW_MSG_TAGS,
} from "../src/protocol.ts"

it("locks the closed HostMsg tag list", () => {
  expect(HOST_MSG_TAGS.length).toBe(Object.keys(HOST_MSG_HANDLED).length)
  expect(HOST_MSG_HANDLED.restoreTranscript).toBe(true)
})

it("locks the closed WebviewMsg tag list", () => {
  expect(WEBVIEW_MSG_TAGS.length).toBe(Object.keys(WEBVIEW_MSG_HANDLED).length)
  expect(WEBVIEW_MSG_HANDLED.cycleMode).toBe(true)
  expect(WEBVIEW_MSG_HANDLED.setMode).toBe(true)
  expect(WEBVIEW_MSG_HANDLED.setModel).toBe(true)
  expect(WEBVIEW_MSG_HANDLED.openSettings).toBe(true)
  expect(WEBVIEW_MSG_HANDLED.commitAllPending).toBe(true)
})

it("decodes a permission card and a send", () => {
  const card = decodeHostMsg({
    _tag: "permissionCard",
    requestId: "1",
    toolCallId: "c1",
    title: "Edit",
    options: [{ optionId: "allow-once", name: "Allow once", kind: "allow_once" }],
    hasDiff: true,
  })
  expect(card._tag).toBe("permissionCard")
  const send = decodeWebviewMsg({
    _tag: "send",
    text: "hello",
    chips: [],
  })
  expect(send._tag).toBe("send")
})

it("maps unknown stop reasons to unknown", () => {
  expect(displayStopReason("end_turn")).toBe("end_turn")
  expect(displayStopReason("tool_use")).toBe("unknown")
})

it("decodes settingsState and setSetting", () => {
  const state = decodeHostMsg({
    _tag: "settingsState",
    cliPath: "",
    nodePath: "",
    includeActiveFileByDefault: true,
    useCtrlEnterToSend: false,
    changesPresentation: "toast",
  })
  expect(state._tag).toBe("settingsState")
  const set = decodeWebviewMsg({
    _tag: "setSetting",
    key: "changesPresentation",
    value: "pane",
  })
  expect(set).toEqual({
    _tag: "setSetting",
    key: "changesPresentation",
    value: "pane",
  })
})

it("decodes an mcpCatalog host message", () => {
  const msg = decodeHostMsg({
    _tag: "mcpCatalog",
    loading: false,
    healthyCount: 1,
    failingCount: 0,
    servers: [{
      name: "metals",
      transport: "stdio",
      source: "~/.grok/config.toml",
      healthy: true,
      toolCount: 12,
      checks: [{ label: "handshake OK", passed: true }],
    }],
  })
  expect(msg._tag).toBe("mcpCatalog")
  if (msg._tag === "mcpCatalog") {
    expect(msg.servers[0]?.name).toBe("metals")
    expect(msg.servers[0]?.toolCount).toBe(12)
  }
})

it("decodes a changesSummary host message", () => {
  const msg = decodeHostMsg({
    _tag: "changesSummary",
    fileCount: 2,
    additions: 10,
    deletions: 3,
  })
  expect(msg).toEqual({
    _tag: "changesSummary",
    fileCount: 2,
    additions: 10,
    deletions: 3,
  })
})

it("decodes a composerChip from Add Selection to Chat", () => {
  const msg = decodeHostMsg({
    _tag: "composerChip",
    path: "plan.md",
    absPath: "/tmp/plan.md",
    startLine: 12,
    endLine: 40,
    excerpt: "Open the preview.",
    source: "selection",
  })
  expect(msg._tag).toBe("composerChip")
  if (msg._tag === "composerChip") {
    expect(msg.excerpt).toBe("Open the preview.")
    expect(msg.startLine).toBe(12)
  }
})

it("decodes editorContext and revealEditor", () => {
  expect(decodeHostMsg({
    _tag: "editorContext",
    path: "a.ts",
    startLine: 1,
    startCol: 1,
    endLine: 3,
    endCol: 2,
    hasSelection: true,
  })).toMatchObject({
    _tag: "editorContext",
    path: "a.ts",
    hasSelection: true,
  })
  expect(decodeWebviewMsg({ _tag: "revealEditor" })).toEqual({ _tag: "revealEditor" })
  expect(decodeWebviewMsg({
    _tag: "revealEditor",
    absPath: "/tmp/plan.md",
    startLine: 12,
    endLine: 40,
  })).toEqual({
    _tag: "revealEditor",
    absPath: "/tmp/plan.md",
    startLine: 12,
    endLine: 40,
  })
})

it("decodes openPlan with optional markdown", () => {
  expect(decodeWebviewMsg({ _tag: "openPlan" })).toEqual({ _tag: "openPlan" })
  expect(decodeWebviewMsg({
    _tag: "openPlan",
    markdown: "# Plan",
  })).toEqual({
    _tag: "openPlan",
    markdown: "# Plan",
  })
})

it("decodes a named MCP refresh", () => {
  expect(decodeWebviewMsg({ _tag: "refreshMcp", name: "metals" })).toEqual({
    _tag: "refreshMcp",
    name: "metals",
  })
})

it("decodes per-tool MCP enable state", () => {
  expect(decodeWebviewMsg({
    _tag: "setMcpToolEnabled",
    name: "metals",
    tool: "compile-file",
    enabled: false,
  })).toEqual({
    _tag: "setMcpToolEnabled",
    name: "metals",
    tool: "compile-file",
    enabled: false,
  })
  const msg = decodeHostMsg({
    _tag: "mcpCatalog",
    loading: false,
    healthyCount: 1,
    failingCount: 0,
    servers: [{
      name: "metals",
      transport: "http",
      source: ".mcp.json",
      healthy: true,
      toolCount: 2,
      tools: [
        { name: "compile-file", enabled: true },
        { name: "test", enabled: false },
      ],
      checks: [],
    }],
  })
  expect(msg._tag).toBe("mcpCatalog")
  if (msg._tag === "mcpCatalog") {
    expect(msg.servers[0]?.tools).toEqual([
      { name: "compile-file", enabled: true },
      { name: "test", enabled: false },
    ])
  }
})

it("decodes sendNow and setReasoning", () => {
  expect(decodeWebviewMsg({ _tag: "sendNow" })).toEqual({ _tag: "sendNow" })
  expect(decodeWebviewMsg({ _tag: "setReasoning", value: "xhigh" })).toEqual({
    _tag: "setReasoning",
    value: "xhigh",
  })
  expect(decodeWebviewMsg({
    _tag: "setReasoning",
    value: "low",
    modelId: "grok-4.6",
  })).toEqual({
    _tag: "setReasoning",
    value: "low",
    modelId: "grok-4.6",
  })
})

it("decodes composer chrome messages", () => {
  expect(decodeWebviewMsg({ _tag: "addSelection" })).toEqual({ _tag: "addSelection" })
  expect(decodeWebviewMsg({ _tag: "setMode", modeId: "plan" })).toEqual({
    _tag: "setMode",
    modeId: "plan",
  })
  expect(decodeWebviewMsg({ _tag: "setModel", modelId: "grok-4.6" })).toEqual({
    _tag: "setModel",
    modelId: "grok-4.6",
  })
  expect(decodeWebviewMsg({ _tag: "openSettings" })).toEqual({ _tag: "openSettings" })
  expect(decodeWebviewMsg({ _tag: "trustFolder" })).toEqual({ _tag: "trustFolder" })
  const meta = decodeHostMsg({
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
  expect(meta._tag).toBe("sessionMeta")
  if (meta._tag === "sessionMeta") {
    expect(meta.availableModes?.[0]?.name).toBe("Plan")
    expect(meta.availableModels?.[0]?.modelId).toBe("grok-4.6")
    expect(meta.availableModels?.[0]?.reasoning?.current).toBe("high")
  }
})

it("decodes a thoughtChunk host message", () => {
  const msg = decodeHostMsg({
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Considering the selection.\nThen I'll answer.\n",
  })
  expect(msg._tag).toBe("thoughtChunk")
  if (msg._tag === "thoughtChunk") {
    expect(msg.text).toContain("Then I'll answer.")
  }
})
