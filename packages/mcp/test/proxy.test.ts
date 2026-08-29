import { expect, it } from "@effect/vitest"
import { MCP_EDITOR_DOWN_MESSAGE } from "@groks-beard/core"
import { chmodSync, existsSync, mkdtempSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { PassThrough } from "node:stream"
import { callBridge, probeBridge } from "../src/bridge-client.ts"
import { listenBridge } from "../src/bridge-server.ts"
import { parseWorkspaceArg, runMcpProxy } from "../src/mcp-stdio.ts"
import { splitNdjson } from "../src/ndjson.ts"
import { SOCKET_MODE, socketAddress } from "../src/socket-address.ts"

const readMessage = (stream: PassThrough): Promise<unknown> =>
  new Promise((resolve, reject) => {
    let buffer = ""
    const onData = (chunk: string | Buffer) => {
      const split = splitNdjson(buffer, typeof chunk === "string" ? chunk : chunk.toString("utf8"))
      buffer = split.rest
      const line = split.lines[0]
      if (line === undefined) return
      stream.off("data", onData)
      try {
        resolve(JSON.parse(line))
      } catch (cause) {
        reject(cause)
      }
    }
    stream.on("data", onData)
    stream.once("error", reject)
  })

it("parses --workspace from argv", () => {
  expect(parseWorkspaceArg(["--workspace", "/abs/ws"])).toBe("/abs/ws")
  expect(parseWorkspaceArg(["--workspace=/abs/ws"])).toBe("/abs/ws")
  expect(parseWorkspaceArg(["--help"])).toBeUndefined()
})

it("exits with McpEditorDown copy when the editor socket is missing", async () => {
  const stdin = new PassThrough()
  const stdout = new PassThrough()
  const stderr = new PassThrough()
  let err = ""
  stderr.on("data", (chunk) => {
    err += String(chunk)
  })
  const code = await runMcpProxy({
    workspace: join(mkdtempSync(join(tmpdir(), "beard-mcp-")), "missing-ws"),
    stdin,
    stdout,
    stderr,
    runtimeDir: tmpdir(),
  })
  expect(code).toBe(1)
  expect(err).toContain(MCP_EDITOR_DOWN_MESSAGE)
})

it("scripted MCP client lists tools and calls editor_workspace_root over the proxy", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-mcp-ws-"))
  const runtimeDir = tmpdir()
  const address = socketAddress({ workspace, win: false, runtimeDir })
  const server = await listenBridge(address, async (request) => {
    if (request.tool === "editor_workspace_root") return { root: workspace }
    if (request.tool === "editor_selection") {
      return {
        path: "a.ts",
        absPath: join(workspace, "a.ts"),
        truncated: false,
        atRef: "@a.ts:1-2",
      }
    }
    throw new Error(`unexpected tool ${request.tool}`)
  })
  const stdin = new PassThrough()
  const stdout = new PassThrough()
  const stderr = new PassThrough()
  try {
    if (existsSync(address)) {
      expect(statSync(address).mode & 0o777).toBe(SOCKET_MODE)
    }
    const running = runMcpProxy({
      workspace,
      stdin,
      stdout,
      stderr,
      runtimeDir,
    })
    const init = readMessage(stdout)
    stdin.write(`${
      JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-03-26",
          capabilities: {},
          clientInfo: { name: "scripted", version: "0" },
        },
      })
    }\n`)
    const initMsg = await init as {
      result: { serverInfo: { name: string }; capabilities: { tools: unknown } }
    }
    expect(initMsg.result.serverInfo.name).toBe("groks-beard")
    expect(initMsg.result.capabilities.tools).toEqual({})
    stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`)
    const listed = readMessage(stdout)
    stdin.write(
      `${
        JSON.stringify({
          jsonrpc: "2.0",
          id: 2,
          method: "tools/list",
          params: { cursor: "unknown" },
        })
      }\n`,
    )
    const listMsg = await listed as {
      result: { tools: Array<{ name: string; annotations: { readOnlyHint: boolean } }> }
    }
    const names = listMsg.result.tools.map((tool) => tool.name)
    expect(names).toContain("editor_open_diff")
    expect(names).toContain("editor_show_changes")
    expect(names).not.toContain("editor_write")
    expect(listMsg.result.tools).toHaveLength(6)
    expect("nextCursor" in listMsg.result).toBe(false)
    expect(listMsg.result.tools.every((tool) => tool.annotations.readOnlyHint)).toBe(true)
    const called = readMessage(stdout)
    stdin.write(`${
      JSON.stringify({
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: { name: "editor_workspace_root", arguments: {} },
      })
    }\n`)
    const callMsg = await called as {
      result: { content: Array<{ text: string }>; isError?: boolean }
    }
    const payload = callMsg.result.content[0]?.text ?? ""
    expect(callMsg.result.isError ?? false, payload).toBe(false)
    expect(JSON.parse(payload)).toEqual({ root: workspace })
    stdin.end()
    expect(await running).toBe(0)
  } finally {
    await server.close()
  }
})

it("replies with a JSON-RPC error and exits when the editor drops mid-session", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-mcp-down-"))
  const runtimeDir = tmpdir()
  const address = socketAddress({ workspace, win: false, runtimeDir })
  const server = await listenBridge(address, async () => ({ root: workspace }))
  const stdin = new PassThrough()
  const stdout = new PassThrough()
  const stderr = new PassThrough()
  let err = ""
  stderr.on("data", (chunk) => {
    err += String(chunk)
  })
  try {
    const running = runMcpProxy({ workspace, stdin, stdout, stderr, runtimeDir })
    const init = readMessage(stdout)
    stdin.write(`${
      JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-03-26",
          capabilities: {},
          clientInfo: { name: "t", version: "0" },
        },
      })
    }\n`)
    await init
    await server.close()
    const failed = readMessage(stdout)
    stdin.write(`${
      JSON.stringify({
        jsonrpc: "2.0",
        id: 7,
        method: "tools/call",
        params: { name: "editor_workspace_root", arguments: {} },
      })
    }\n`)
    const failMsg = await failed as { id: number; error?: { message: string } }
    expect(failMsg.id).toBe(7)
    expect(failMsg.error?.message).toContain("Enable TUI Bridge")
    expect(await running).toBe(1)
    expect(err).toContain(MCP_EDITOR_DOWN_MESSAGE)
  } finally {
    stdin.end()
  }
})

it("survives a connect-and-close probe without taking down the bridge", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-mcp-probe-"))
  const address = socketAddress({ workspace, win: false, runtimeDir: tmpdir() })
  const server = await listenBridge(address, async () => ({ root: workspace }))
  try {
    await probeBridge(address, workspace)
    await probeBridge(address, workspace)
    const result = await callBridge(address, workspace, {
      id: "1",
      tool: "editor_workspace_root",
    })
    expect(result).toEqual({ root: workspace })
  } finally {
    await server.close()
  }
})

it("binds a unix socket at the hashed address with mode 0600", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-mcp-bind-"))
  const address = socketAddress({ workspace, win: false, runtimeDir: tmpdir() })
  const server = await listenBridge(address, async () => ({ ok: true }))
  try {
    expect(existsSync(address)).toBe(true)
    expect(statSync(address).mode & 0o777).toBe(SOCKET_MODE)
    chmodSync(address, 0o600)
    expect(address.endsWith(".sock")).toBe(true)
    expect(address).toContain("/groks-beard/")
  } finally {
    await server.close()
    expect(existsSync(address)).toBe(false)
  }
})
