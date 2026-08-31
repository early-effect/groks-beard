import { expect, it } from "@effect/vitest"
import { connectBeardAcp, FakeGrokAgent } from "@groks-beard/acp"
import type { HostMsg } from "@groks-beard/core"
import { mkdtempSync, writeFileSync } from "node:fs"
import { createServer } from "node:http"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { ChatRuntime, mergeSessionModes } from "../src/chat-runtime.ts"
import { ComposerState } from "../src/composer.ts"

it("keeps the TUI four modes even when the agent omits Auto", () => {
  expect(
    mergeSessionModes([
      { id: "normal", name: "Normal" },
      { id: "plan", name: "Plan" },
      { id: "always-approve", name: "Always approve" },
    ]).map((mode) => mode.id),
  ).toEqual(["normal", "auto", "plan", "always-approve"])
  expect(mergeSessionModes(undefined).map((mode) => mode.id)).toEqual([
    "normal",
    "auto",
    "plan",
    "always-approve",
  ])
})

it("streams thought chunks into HostMsg before the agent reply", async () => {
  const posts: Array<HostMsg> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.attachSession({ sessionId: fake.sessionId, modeId: "normal" })
  await runtime.send("explain this", [])
  await new Promise((resolve) => setTimeout(resolve, 0))
  const thoughts = posts.filter((msg) => msg._tag === "thoughtChunk")
  expect(thoughts.map((msg) => msg._tag === "thoughtChunk" ? msg.text : "")).toEqual([
    "Considering the selection.\n",
    "Then I'll answer.\n",
  ])
  expect(posts.some((msg) => msg._tag === "agentChunk" && msg.text === "hello")).toBe(true)
  const thoughtIndex = posts.findIndex((msg) => msg._tag === "thoughtChunk")
  const agentIndex = posts.findIndex((msg) => msg._tag === "agentChunk")
  expect(thoughtIndex).toBeGreaterThan(-1)
  expect(thoughtIndex).toBeLessThan(agentIndex)
  expect(posts.some((msg) => msg._tag === "turnEnd" && msg.stopReason === "end_turn")).toBe(true)
  expect(
    posts.some((msg) =>
      msg._tag === "sessionMeta" && msg.occupancy?.used === 12000 && msg.occupancy.size === 500000
    ),
  ).toBe(true)
  beard.connection.close()
})

it("does not send a permission result when the user only opens the diff", async () => {
  const posts: Array<HostMsg> = []
  const opened: Array<string> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
    onPermission: (params, requestId) =>
      holder.runtime?.onPermission(params, requestId) ?? { outcome: { outcome: "cancelled" } },
  })
  let resolved = false
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
    openDiff: (requestId) => {
      opened.push(requestId)
    },
  })
  holder.runtime = runtime
  const pending = runtime.onPermission({
    sessionId: fake.sessionId,
    toolCall: {
      toolCallId: "call_1",
      title: "Edit",
      content: [{ type: "diff", path: "/tmp/file.ts", oldText: "old", newText: "new" }],
    },
    options: [{ optionId: "allow-once", name: "Allow once", kind: "allow_once" }],
  }, "perm-1")
  void pending.then(() => {
    resolved = true
  })
  runtime.openDiff("perm-1")
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(opened).toEqual(["perm-1"])
  expect(resolved).toBe(false)
  expect(posts.some((msg) => msg._tag === "permissionCard" && msg.hasDiff)).toBe(true)
  runtime.permissionChoice("perm-1", "allow-once")
  await expect(pending).resolves.toEqual({
    outcome: { outcome: "selected", optionId: "allow-once" },
  })
  expect(resolved).toBe(true)
  beard.connection.close()
})

it("notifies ingestUpdate for tool_call diffs in every mode", async () => {
  const ingested: Array<unknown> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: () => undefined,
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
    ingestUpdate: (params) => {
      ingested.push(params)
    },
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.attachSession({ sessionId: fake.sessionId, modeId: "always-approve" })
  await runtime.send("edit the file", [])
  await new Promise((resolve) => setTimeout(resolve, 0))
  const tool = ingested.some((params) =>
    JSON.stringify(params).includes("tool_call") && JSON.stringify(params).includes("/tmp/file.ts")
  )
  expect(tool).toBe(true)
  expect(runtime.currentTurnTitle).toBe("edit the file")
  beard.connection.close()
})

it("posts mode and model chrome after picker changes", async () => {
  const posts: Array<HostMsg> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.ensureSession()
  await runtime.setMode("plan")
  await runtime.setModel("grok-4.5")
  const metas = posts.filter((msg) => msg._tag === "sessionMeta")
  const last = metas[metas.length - 1]
  expect(last).toMatchObject({
    _tag: "sessionMeta",
    modeId: "plan",
    modelId: "grok-4.5",
  })
  if (last?._tag === "sessionMeta") {
    expect(last.availableModes?.map((mode) => mode.id)).toEqual([
      "normal",
      "auto",
      "plan",
      "always-approve",
    ])
    expect(last.availableModels?.map((model) => model.modelId)).toEqual([
      "grok-4.5",
      "grok-4.6",
    ])
    expect(last.availableModels?.[1]?.reasoning?.options.map((item) => item.value)).toEqual([
      "xhigh",
      "high",
      "medium",
      "low",
    ])
  }
  beard.connection.close()
})

it("updates occupancy from Grok _meta.totalTokens against the model window", async () => {
  const posts: Array<HostMsg> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.attachSession({
    sessionId: fake.sessionId,
    modeId: "normal",
    modelId: "grok-4.6",
  })
  runtime.onSessionUpdate({
    sessionId: fake.sessionId,
    update: {
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "hi" },
    },
    _meta: { totalTokens: 29183 },
  })
  expect(posts.some((msg) =>
    msg._tag === "sessionMeta"
    && msg.occupancy?.used === 29183
    && msg.occupancy.size === 500000
  )).toBe(true)
  beard.connection.close()
})

it("sends a queued follow-up immediately", async () => {
  const posts: Array<HostMsg> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.attachSession({ sessionId: fake.sessionId, modeId: "normal" })
  await runtime.queueFollowUp("send this now", [])
  expect(posts.some((msg) => msg._tag === "queued" && msg.count === 1)).toBe(true)
  await runtime.sendQueuedNow()
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(posts.some((msg) => msg._tag === "userMessage" && msg.text === "send this now")).toBe(true)
  beard.connection.close()
})

it("refreshes a named stdio MCP without respawning the session", async () => {
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: () => undefined,
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.attachSession({ sessionId: fake.sessionId, modeId: "normal" })
  expect(await runtime.refreshMcp("playwright")).toBe("ok")
  beard.connection.close()
})

it("asks to respawn when HTTP MCP is up but the live session cannot reload it", async () => {
  const cwd = mkdtempSync(join(tmpdir(), "beard-refresh-"))
  const server = createServer((_req, res) => {
    res.setHeader("content-type", "application/json")
    res.end(JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      result: { protocolVersion: "2025-11-25" },
    }))
  })
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()))
  const address = server.address()
  if (address === null || typeof address === "string") {
    server.close()
    throw new Error("expected tcp")
  }
  writeFileSync(
    join(cwd, ".mcp.json"),
    JSON.stringify({
      mcpServers: {
        metals: { url: `http://127.0.0.1:${address.port}/mcp`, type: "http" },
      },
    }),
  )
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: () => undefined,
    composer: new ComposerState(),
    cwd,
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  try {
    await beard.agent.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: {},
    })
    await runtime.attachSession({ sessionId: fake.sessionId, modeId: "normal" })
    expect(await runtime.refreshMcp("metals")).toBe("respawn")
  } finally {
    beard.connection.close()
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }
})

it("does not invent a reasoning menu when the session and models omit it", async () => {
  const posts: Array<HostMsg> = []
  const runtime = new ChatRuntime({
    agent: {} as never,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  await runtime.attachSession({
    sessionId: "s",
    modeId: "normal",
    modelId: "vertigo-qwen",
    availableModels: [{ modelId: "vertigo-qwen", name: "Qwen 3.8 27B" }],
  })
  const last = posts[posts.length - 1]
  expect(last?._tag).toBe("sessionMeta")
  if (last?._tag === "sessionMeta") {
    expect(last.reasoning).toBeUndefined()
    expect(last.availableModels?.[0]?.reasoning).toBeUndefined()
  }
})

it("overlays catalog reasoning onto ACP models that omit it", async () => {
  const posts: Array<HostMsg> = []
  const runtime = new ChatRuntime({
    agent: {} as never,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
    modelCatalog: () => ({
      models: {
        "grok-4.6": {
          info: {
            reasoning_effort: "high",
            reasoning_efforts: [
              { value: "xhigh", label: "Extra high" },
              { value: "high", label: "High", default: true },
            ],
          },
        },
      },
    }),
  })
  await runtime.attachSession({
    sessionId: "s",
    modeId: "normal",
    modelId: "grok-4.6",
    availableModels: [
      { modelId: "grok-4.6", name: "Grok 4.6" },
      { modelId: "vertigo-qwen", name: "Qwen 3.8 27B" },
    ],
  })
  const last = posts[posts.length - 1]
  expect(last?._tag).toBe("sessionMeta")
  if (last?._tag === "sessionMeta") {
    expect(last.reasoning?.current).toBe("high")
    expect(last.availableModels?.[0]?.reasoning?.options.map((item) => item.value)).toEqual([
      "xhigh",
      "high",
    ])
    expect(last.availableModels?.[1]?.reasoning).toBeUndefined()
  }
})

it("switches model then sets reasoning from the model list", async () => {
  const posts: Array<HostMsg> = []
  const fake = new FakeGrokAgent()
  const holder: { runtime?: ChatRuntime } = {}
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => holder.runtime?.onSessionUpdate(params),
  })
  const runtime = new ChatRuntime({
    agent: beard.agent,
    post: (msg) => posts.push(msg),
    composer: new ComposerState(),
    cwd: "/tmp/proj",
    includeActiveFileByDefault: () => false,
  })
  holder.runtime = runtime
  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
  })
  await runtime.ensureSession()
  await runtime.setReasoning("low", "grok-4.5")
  const last = posts.filter((msg) => msg._tag === "sessionMeta").at(-1)
  expect(last).toMatchObject({
    _tag: "sessionMeta",
    modelId: "grok-4.5",
    reasoning: { current: "low" },
  })
  if (last?._tag === "sessionMeta") {
    expect(last.availableModels?.find((model) => model.modelId === "grok-4.5")?.reasoning?.current)
      .toBe("low")
  }
  beard.connection.close()
})
