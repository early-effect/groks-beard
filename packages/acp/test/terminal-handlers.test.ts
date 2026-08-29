import { expect, it } from "@effect/vitest"
import { PLAN_BLOCKED_CODE } from "@groks-beard/core"
import { connectBeardAcp } from "../src/client.ts"
import { encodeNdjsonChunk } from "../src/ndjson.ts"
import { MemoryTerminalManager } from "../src/terminal-manager.ts"

const feedCreate = (
  beard: ReturnType<typeof connectBeardAcp>,
  id: string,
  command: string,
  args?: ReadonlyArray<string>,
  sessionId = "s",
) => {
  beard.transport.feedFromAgent(encodeNdjsonChunk([{
    jsonrpc: "2.0",
    id,
    method: "terminal/create",
    params: {
      sessionId,
      command,
      ...(args !== undefined ? { args } : {}),
    },
  }]))
}

const resultById = (results: Array<unknown>, id: string): Record<string, unknown> | undefined => {
  const row = results.find((item) =>
    typeof item === "object" && item !== null && "id" in item && (item as { id: unknown }).id === id
  )
  return row as Record<string, unknown> | undefined
}

it("runs create/output/wait/release and rejects mutating create while planActive", async () => {
  const terminal = new MemoryTerminalManager()
  const results: Array<unknown> = []
  const beard = connectBeardAcp({
    terminal,
    onOutgoing: (message) => {
      results.push(message)
    },
  })
  feedCreate(beard, "t-ls", "ls")
  await new Promise((resolve) => setTimeout(resolve, 20))
  const created = results.find((row) =>
    typeof row === "object" && row !== null && "result" in row
    && typeof (row as { result?: { terminalId?: string } }).result?.terminalId === "string"
  ) as { result: { terminalId: string } }
  expect(created.result.terminalId).toMatch(/^mem-term-/)
  beard.transport.feedFromAgent(encodeNdjsonChunk([
    {
      jsonrpc: "2.0",
      id: "t-out",
      method: "terminal/output",
      params: { sessionId: "s", terminalId: created.result.terminalId },
    },
    {
      jsonrpc: "2.0",
      id: "t-wait",
      method: "terminal/wait_for_exit",
      params: { sessionId: "s", terminalId: created.result.terminalId },
    },
    {
      jsonrpc: "2.0",
      id: "t-kill",
      method: "terminal/kill",
      params: { sessionId: "s", terminalId: created.result.terminalId },
    },
    {
      jsonrpc: "2.0",
      id: "t-rel",
      method: "terminal/release",
      params: { sessionId: "s", terminalId: created.result.terminalId },
    },
  ]))
  await new Promise((resolve) => setTimeout(resolve, 20))
  expect(
    results.some((row) =>
      typeof row === "object" && row !== null && "id" in row
      && (row as { id: unknown }).id === "t-out"
    ),
  ).toBe(true)
  expect(
    results.some((row) =>
      typeof row === "object" && row !== null && "id" in row
      && (row as { id: unknown }).id === "t-wait"
    ),
  ).toBe(true)
  expect(resultById(results, "t-kill")?.result).toEqual({})
  expect(resultById(results, "t-rel")?.result).toEqual({})

  beard.state.planActive = true
  beard.state.modeId = "plan"
  feedCreate(beard, "t-ls-plan", "ls")
  await new Promise((resolve) => setTimeout(resolve, 20))
  const allowed = results.find((row) =>
    typeof row === "object" && row !== null && "id" in row
    && (row as { id: unknown }).id === "t-ls-plan"
  ) as { result?: { terminalId?: string }; error?: { code?: number } }
  expect(allowed.result?.terminalId).toMatch(/^mem-term-/)
  feedCreate(beard, "t-rm", "rm", ["-rf", "/tmp/x"], "parent")
  feedCreate(beard, "t-rm-sub", "rm", ["-rf", "/tmp/y"], "subagent")
  await new Promise((resolve) => setTimeout(resolve, 20))
  expect((resultById(results, "t-rm")?.error as { code?: number } | undefined)?.code).toBe(
    PLAN_BLOCKED_CODE,
  )
  expect((resultById(results, "t-rm-sub")?.error as { code?: number } | undefined)?.code).toBe(
    PLAN_BLOCKED_CODE,
  )
  beard.terminal.dispose()
  beard.connection.close()
})
