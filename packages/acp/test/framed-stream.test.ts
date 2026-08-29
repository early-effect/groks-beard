import { expect, it } from "@effect/vitest"
import { createFramedTransport } from "../src/framed-stream.ts"
import { encodeNdjsonChunk } from "../src/ndjson.ts"
import { emptySessionState } from "../src/session-state.ts"

it("commits set_mode from the first line of a two-line chunk before enqueueing the second", async () => {
  const state = emptySessionState()
  const transport = createFramedTransport(state)
  const writer = transport.stream.writable.getWriter()
  await writer.write({
    jsonrpc: "2.0",
    id: 1,
    method: "session/set_mode",
    params: { sessionId: "s", modeId: "plan" },
  })

  expect(state.modeId).toBeUndefined()

  transport.feedFromAgent(encodeNdjsonChunk([
    { jsonrpc: "2.0", id: 1, result: {} },
    {
      jsonrpc: "2.0",
      id: "term-1",
      method: "terminal/create",
      params: { sessionId: "s", command: "rm" },
    },
  ]))

  expect(state.modeId).toBe("plan")
  expect(state.planActive).toBe(true)

  const reader = transport.stream.readable.getReader()
  const first = await reader.read()
  expect("result" in (first.value ?? {})).toBe(true)
  const second = await reader.read()
  expect(second.value && "method" in second.value && second.value.method).toBe("terminal/create")
  transport.close()
})

it("commits session/new currentModeId before the next line", async () => {
  const state = emptySessionState()
  const transport = createFramedTransport(state)
  const writer = transport.stream.writable.getWriter()
  await writer.write({
    jsonrpc: "2.0",
    id: 1,
    method: "session/new",
    params: { cwd: "/tmp", mcpServers: [] },
  })
  transport.feedFromAgent(encodeNdjsonChunk([
    {
      jsonrpc: "2.0",
      id: 1,
      result: { sessionId: "s", modes: { currentModeId: "plan" } },
    },
    {
      jsonrpc: "2.0",
      id: "term-1",
      method: "terminal/create",
      params: { sessionId: "s", command: "rm" },
    },
  ]))
  expect(state.modeId).toBe("plan")
  expect(state.planActive).toBe(true)
  transport.close()
})

it("commits session/load currentModeId before the next line", async () => {
  const state = emptySessionState()
  const transport = createFramedTransport(state)
  const writer = transport.stream.writable.getWriter()
  await writer.write({
    jsonrpc: "2.0",
    id: 1,
    method: "session/load",
    params: { sessionId: "s", cwd: "/tmp", mcpServers: [] },
  })
  transport.feedFromAgent(encodeNdjsonChunk([
    {
      jsonrpc: "2.0",
      id: 1,
      result: { sessionId: "s", modes: { currentModeId: "plan" } },
    },
    {
      jsonrpc: "2.0",
      id: "term-1",
      method: "terminal/create",
      params: { sessionId: "s", command: "rm" },
    },
  ]))
  expect(state.planActive).toBe(true)
  transport.close()
})

it("commits current_mode_update before the next line", () => {
  const state = emptySessionState()
  const transport = createFramedTransport(state)
  transport.feedFromAgent(encodeNdjsonChunk([
    {
      jsonrpc: "2.0",
      method: "session/update",
      params: {
        sessionId: "s",
        update: { sessionUpdate: "current_mode_update", currentModeId: "plan" },
      },
    },
    {
      jsonrpc: "2.0",
      id: "term-1",
      method: "terminal/create",
      params: { sessionId: "s", command: "rm" },
    },
  ]))
  expect(state.modeId).toBe("plan")
  expect(state.planActive).toBe(true)
  transport.close()
})
