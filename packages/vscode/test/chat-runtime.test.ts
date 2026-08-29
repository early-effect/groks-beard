import { expect, it } from "@effect/vitest"
import { connectBeardAcp, FakeGrokAgent } from "@groks-beard/acp"
import type { HostMsg } from "@groks-beard/core"
import { ChatRuntime } from "../src/chat-runtime.ts"
import { ComposerState } from "../src/composer.ts"

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
