import { expect, it } from "@effect/vitest"
import { decodeSessionUpdate, PLAN_BLOCKED_CODE, UnknownUpdate } from "@groks-beard/core"
import { connectBeardAcp } from "../src/client.ts"
import { FakeGrokAgent, requestPermissionEdit } from "../src/fake-agent.ts"
import { JSON_RPC_METHOD_NOT_FOUND } from "../src/methods.ts"
import { encodeNdjsonChunk } from "../src/ndjson.ts"

it("commits session/set_mode before the next line in the same stdout chunk", async () => {
  const fake = new FakeGrokAgent({ pairSetModeWithTerminal: true })
  const seen = Promise.withResolvers<{ modeId: string; planActive: boolean }>()
  const blocked = Promise.withResolvers<number>()
  const beard = connectBeardAcp({
    fake,
    onTerminalCreate: (_command, state) => {
      seen.resolve({ modeId: state.modeId ?? "unset", planActive: state.planActive })
    },
    onOutgoing: (message) => {
      if ("error" in message && message.error !== undefined && typeof message.error === "object") {
        const code = (message.error as { code?: number }).code
        if (code === PLAN_BLOCKED_CODE) blocked.resolve(code)
      }
    },
  })

  await beard.agent.request("initialize", {
    protocolVersion: 1,
    clientCapabilities: {},
    clientInfo: { name: "groks-beard", title: "Grok's Beard", version: "0.0.0" },
  })
  await beard.agent.request("session/new", { cwd: "/tmp/proj", mcpServers: [] })
  await beard.agent.request("session/set_mode", {
    sessionId: fake.sessionId,
    modeId: "plan",
  })

  expect(beard.state.modeId).toBe("plan")
  expect(beard.state.planActive).toBe(true)
  expect(await seen.promise).toEqual({ modeId: "plan", planActive: true })
  expect(await blocked.promise).toBe(PLAN_BLOCKED_CODE)
  beard.terminal.dispose()
  beard.connection.close()
})

it("encodes two JSON-RPC lines in one stdout chunk", () => {
  const fake = new FakeGrokAgent({ pairSetModeWithTerminal: true })
  const bytes = fake.encodeReplies({
    jsonrpc: "2.0",
    id: 7,
    method: "session/set_mode",
    params: { sessionId: "sess_test", modeId: "plan" },
  })
  const text = new TextDecoder().decode(bytes)
  const lines = text.trimEnd().split("\n")
  expect(lines).toHaveLength(2)
  expect(JSON.parse(lines[0]!).id).toBe(7)
  expect(JSON.parse(lines[1]!).method).toBe("terminal/create")
})

it("session/load lock is a JSON-RPC error", async () => {
  const fake = new FakeGrokAgent({ lockLoad: true })
  const beard = connectBeardAcp({ fake })
  await beard.agent.request("initialize", { protocolVersion: 1, clientCapabilities: {} })
  await expect(
    beard.agent.request("session/load", {
      sessionId: fake.sessionId,
      cwd: "/tmp/proj",
      mcpServers: [],
    }),
  ).rejects.toMatchObject({ code: JSON_RPC_METHOD_NOT_FOUND })
  beard.connection.close()
})

it("unknown methods are -32601", async () => {
  const fake = new FakeGrokAgent()
  const beard = connectBeardAcp({ fake })
  await beard.agent.request("initialize", { protocolVersion: 1, clientCapabilities: {} })
  await expect(beard.agent.request("_x.ai/not-a-method", {})).rejects.toMatchObject({
    code: JSON_RPC_METHOD_NOT_FOUND,
  })
  beard.connection.close()
})

it("permission cards carry a region diff", async () => {
  const recorded: Array<string> = []
  const fake = new FakeGrokAgent()
  const beard = connectBeardAcp({
    fake,
    onPermission: (params) => {
      recorded.push(JSON.stringify(params))
      return { outcome: { outcome: "selected", optionId: "allow-once" } }
    },
  })
  await beard.agent.request("initialize", { protocolVersion: 1, clientCapabilities: {} })
  beard.transport.feedFromAgent(encodeNdjsonChunk([requestPermissionEdit(fake.sessionId)]))
  await new Promise((resolve) => setTimeout(resolve, 30))
  expect(recorded.length).toBe(1)
  expect(recorded[0]).toContain('"type":"diff"')
  expect(recorded[0]).toContain("old")
  beard.connection.close()
})

it("unknown sessionUpdate does not throw", () => {
  const update = decodeSessionUpdate({ sessionUpdate: "brand_new_event", extra: true })
  expect(update).toBeInstanceOf(UnknownUpdate)
})
