import { expect, it } from "@effect/vitest"
import { hostMsgsFromSessionUpdate } from "@groks-beard/core"
import { connectBeardAcp } from "../src/client.ts"
import { FakeGrokAgent } from "../src/fake-agent.ts"

const waitForMacrotask = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))

it("session/prompt forwards thought chunks before the agent message", async () => {
  const updates: Array<unknown> = []
  const fake = new FakeGrokAgent()
  const beard = connectBeardAcp({
    fake,
    onSessionUpdate: (params) => updates.push(params),
  })
  await beard.agent.request("initialize", { protocolVersion: 1, clientCapabilities: {} })
  await beard.agent.request("session/new", { cwd: "/tmp/proj", mcpServers: [] })
  await beard.agent.request("session/prompt", {
    sessionId: fake.sessionId,
    prompt: [{ type: "text", text: "hi" }],
  })
  await waitForMacrotask()
  const tags = updates.map((params) => {
    const rec = params as { update?: { sessionUpdate?: string } }
    return rec.update?.sessionUpdate
  })
  expect(tags.filter((tag) => tag === "agent_thought_chunk")).toHaveLength(2)
  expect(tags.indexOf("agent_thought_chunk")).toBeLessThan(tags.indexOf("agent_message_chunk"))
  const thoughtText = updates.flatMap((params) => hostMsgsFromSessionUpdate(params, "turn_1"))
    .filter((msg) => msg._tag === "thoughtChunk")
    .map((msg) => msg.text)
    .join("")
  expect(thoughtText).toBe("Considering the selection.\nThen I'll answer.\n")
  beard.connection.close()
})
