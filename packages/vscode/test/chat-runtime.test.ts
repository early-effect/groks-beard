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
