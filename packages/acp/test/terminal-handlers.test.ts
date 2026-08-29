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
) => {
  beard.transport.feedFromAgent(encodeNdjsonChunk([{
    jsonrpc: "2.0",
    id,
    method: "terminal/create",
    params: {
      sessionId: "s",
      command,
      ...(args !== undefined ? { args } : {}),
    },
  }]))
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

  beard.state.planActive = true
  beard.state.modeId = "plan"
  feedCreate(beard, "t-ls-plan", "ls")
  await new Promise((resolve) => setTimeout(resolve, 20))
  const allowed = results.find((row) =>
    typeof row === "object" && row !== null && "id" in row
    && (row as { id: unknown }).id === "t-ls-plan"
  ) as { result?: { terminalId?: string }; error?: { code?: number } }
  expect(allowed.result?.terminalId).toMatch(/^mem-term-/)
  feedCreate(beard, "t-rm", "rm", ["-rf", "/tmp/x"])
  await new Promise((resolve) => setTimeout(resolve, 20))
  const blocked = results.find((row) =>
    typeof row === "object" && row !== null && "id" in row && (row as { id: unknown }).id === "t-rm"
  ) as { error?: { code?: number } }
  expect(blocked.error?.code).toBe(PLAN_BLOCKED_CODE)
  beard.terminal.dispose()
  beard.connection.close()
})
