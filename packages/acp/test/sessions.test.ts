import { expect, it } from "@effect/vitest"
import { SessionLoadFailed, SessionLocked } from "@groks-beard/core"
import {
  classifySessionLoadError,
  listSessionMcp,
  loadErrorCopy,
  mcpReloadAttempts,
  parseNewSessionResult,
  reloadSessionMcp,
  toggleSessionMcpTool,
} from "../src/sessions.ts"

it("classifies lock-shaped load failures", () => {
  const locked = classifySessionLoadError(new Error("session locked"), "s1", "/tmp/p")
  expect(locked).toBeInstanceOf(SessionLocked)
  expect(loadErrorCopy(locked).actions).toEqual(["fork", "openTui", "retry"])
})

it("classifies other load failures as SessionLoadFailed", () => {
  const failed = classifySessionLoadError(new Error("no such session"), "s1", "/tmp/p")
  expect(failed).toBeInstanceOf(SessionLoadFailed)
  expect(loadErrorCopy(failed).actions).toEqual(["retry"])
})

it("reads modes and models from session/new", () => {
  expect(parseNewSessionResult({
    sessionId: "s",
    modes: {
      currentModeId: "plan",
      availableModes: [
        { id: "normal", name: "Normal" },
        { id: "plan", name: "Plan" },
      ],
    },
    models: {
      currentModelId: "grok-4.6",
      availableModels: [
        { modelId: "grok-4.5", name: "Grok 4.5" },
        { modelId: "grok-4.6", name: "Grok 4.6", description: "Latest" },
      ],
    },
  })).toEqual({
    sessionId: "s",
    modeId: "plan",
    availableModes: [
      { id: "normal", name: "Normal" },
      { id: "plan", name: "Plan" },
    ],
    modelId: "grok-4.6",
    availableModels: [
      { modelId: "grok-4.5", name: "Grok 4.5" },
      { modelId: "grok-4.6", name: "Grok 4.6", description: "Latest" },
    ],
  })
})

it("reads context windows and reasoning options from session/new", () => {
  const created = parseNewSessionResult({
    sessionId: "s",
    models: {
      currentModelId: "grok-4.6",
      availableModels: [
        { modelId: "grok-4.6", name: "Grok 4.6", context_window: 500000 },
      ],
    },
    configOptions: [{
      id: "reasoning_effort",
      name: "Reasoning",
      category: "thought_level",
      currentValue: "high",
      options: [
        { value: "low", name: "Low" },
        { value: "high", name: "High" },
      ],
    }],
  })
  expect(created.availableModels?.[0]?.contextWindow).toBe(500000)
  expect(created.reasoning).toEqual({
    id: "reasoning_effort",
    current: "high",
    options: [
      { value: "low", name: "Low" },
      { value: "high", name: "High" },
    ],
  })
})

it("reads per-model reasoning_efforts from session/new", () => {
  const created = parseNewSessionResult({
    sessionId: "s",
    models: {
      currentModelId: "grok-4.6",
      availableModels: [
        {
          modelId: "grok-4.6",
          name: "Grok 4.6",
          reasoning_effort: "high",
          reasoning_efforts: [
            { value: "xhigh", name: "Extra high" },
            { value: "high", name: "High" },
          ],
        },
        { modelId: "vertigo-qwen", name: "Qwen 3.8 27B" },
      ],
    },
  })
  expect(created.availableModels?.[0]?.reasoning).toEqual({
    current: "high",
    options: [
      { value: "xhigh", name: "Extra high" },
      { value: "high", name: "High" },
    ],
  })
  expect(created.availableModels?.[1]?.reasoning).toBeUndefined()
})

it("reloads a named MCP via setup before the global reload methods", () => {
  const attempts = mcpReloadAttempts("s", "metals")
  expect(attempts[0]).toEqual({
    method: "_x.ai/mcp/setup",
    params: { sessionId: "s", name: "metals" },
  })
  expect(attempts.map((row) => row.method)).not.toContain("_x.ai/mcp/list")
})

it("does not treat mcp/list as a successful reload", async () => {
  const agent = {
    request: async (method: string) => {
      if (method === "_x.ai/mcp/list") return { servers: [] }
      throw new Error("not a reload")
    },
  }
  expect(await reloadSessionMcp(agent as never, "s", "metals")).toBe(false)
})

it("lists MCP tools and toggles one by snake_case ACP params", async () => {
  const calls: Array<{ method: string; params: unknown }> = []
  const agent = {
    request: async (method: string, params: unknown) => {
      calls.push({ method, params })
      if (method === "_x.ai/mcp/list") {
        return {
          result: {
            servers: [{
              name: "metals",
              session: {
                tools: [{ name: "compile-file", enabled: true }],
              },
            }],
          },
        }
      }
      return { result: { ok: true } }
    },
  }
  expect(await listSessionMcp(agent as never, "s1")).toEqual({
    result: {
      servers: [{
        name: "metals",
        session: { tools: [{ name: "compile-file", enabled: true }] },
      }],
    },
  })
  await toggleSessionMcpTool(agent as never, "s1", "metals", "compile-file", false)
  expect(calls).toEqual([
    { method: "_x.ai/mcp/list", params: { sessionId: "s1" } },
    {
      method: "_x.ai/mcp/toggle_tool",
      params: {
        session_id: "s1",
        server_name: "metals",
        tool_name: "compile-file",
        enabled: false,
      },
    },
  ])
})

it("stops at the first MCP reload method that succeeds", async () => {
  const calls: Array<string> = []
  const agent = {
    request: async (method: string) => {
      calls.push(method)
      if (method === "_x.ai/mcp/setup") return {}
      throw new Error("no")
    },
  }
  expect(await reloadSessionMcp(agent as never, "s", "metals")).toBe(true)
  expect(calls).toEqual(["_x.ai/mcp/setup"])
})

it("drops blank mode and model entries from session/new", () => {
  expect(parseNewSessionResult({
    sessionId: "s",
    modes: { currentModeId: "normal", availableModes: [{ id: "" }, { id: "plan" }] },
    models: { availableModels: [{ name: "Nope" }, { modelId: "grok-4.6" }] },
  })).toEqual({
    sessionId: "s",
    modeId: "normal",
    availableModes: [{ id: "plan", name: "plan" }],
    availableModels: [{ modelId: "grok-4.6", name: "grok-4.6" }],
  })
})
