import { expect, it } from "@effect/vitest"
import { mkdtempSync, writeFileSync } from "node:fs"
import { createServer } from "node:http"
import { tmpdir } from "node:os"
import { join } from "node:path"
import {
  httpMcpReady,
  loopbackUrl,
  MCP_RETRY_DELAYS_MS,
  parseProjectHttpMcpServers,
  projectHttpMcpUrlsFor,
  readProjectHttpMcpServers,
  waitForHttpMcpWithBackoff,
} from "../src/project-mcp.ts"

it("reads HTTP metals from a project .mcp.json and skips stdio servers", () => {
  const servers = parseProjectHttpMcpServers({
    mcpServers: {
      metals: { url: "http://localhost:58134/mcp", type: "http" },
      shell: { command: "npx", args: ["-y", "whatever"] },
      linear: {
        url: "https://mcp.linear.app/mcp",
        type: "sse",
        headers: { Authorization: "Bearer tok" },
      },
    },
  })
  expect(servers).toEqual([
    { name: "metals", url: "http://localhost:58134/mcp" },
    { name: "linear", url: "https://mcp.linear.app/mcp" },
  ])
})

it("treats a url-only entry as HTTP", () => {
  expect(parseProjectHttpMcpServers({
    servers: { metals: { url: "http://127.0.0.1:9/mcp" } },
  })).toEqual([
    { name: "metals", url: "http://127.0.0.1:9/mcp" },
  ])
})

it("reads .mcp.json from the workspace cwd and rewrites localhost to 127.0.0.1", () => {
  const cwd = mkdtempSync(join(tmpdir(), "beard-mcp-"))
  writeFileSync(
    join(cwd, ".mcp.json"),
    JSON.stringify({ mcpServers: { metals: { url: "http://localhost:58134/mcp", type: "http" } } }),
  )
  expect(readProjectHttpMcpServers(cwd)).toEqual([
    { name: "metals", url: "http://127.0.0.1:58134/mcp" },
  ])
  expect(readProjectHttpMcpServers(join(cwd, "missing"))).toEqual([])
})

it("rewrites localhost MCP URLs onto IPv4 loopback", () => {
  expect(loopbackUrl("http://localhost:58134/mcp")).toBe("http://127.0.0.1:58134/mcp")
  expect(loopbackUrl("http://127.0.0.1:58134/mcp")).toBe("http://127.0.0.1:58134/mcp")
})

it("lists HTTP URLs for one named server", () => {
  const cwd = mkdtempSync(join(tmpdir(), "beard-mcp-name-"))
  writeFileSync(
    join(cwd, ".mcp.json"),
    JSON.stringify({
      mcpServers: {
        metals: { url: "http://localhost:58134/mcp", type: "http" },
        other: { url: "http://127.0.0.1:9/mcp" },
      },
    }),
  )
  expect(projectHttpMcpUrlsFor(cwd, "metals")).toEqual(["http://127.0.0.1:58134/mcp"])
  expect(projectHttpMcpUrlsFor(cwd, "playwright")).toEqual([])
})

it("probes an HTTP MCP initialize handshake", async () => {
  const server = createServer((req, res) => {
    if (req.method !== "POST") {
      res.statusCode = 400
      res.end()
      return
    }
    res.setHeader("content-type", "application/json")
    res.end(JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      result: { protocolVersion: "2025-11-25", capabilities: {}, serverInfo: { name: "metals" } },
    }))
  })
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()))
  const address = server.address()
  if (address === null || typeof address === "string") {
    server.close()
    throw new Error("expected tcp address")
  }
  const url = `http://127.0.0.1:${address.port}/mcp`
  try {
    expect(await httpMcpReady(url)).toBe(true)
    expect(await httpMcpReady("http://127.0.0.1:1/mcp")).toBe(false)
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }
})

it("uses a finite exponential backoff for MCP reconnect", () => {
  expect([...MCP_RETRY_DELAYS_MS]).toEqual([250, 500, 1000, 2000])
})

it("retries HTTP MCP with backoff until the server answers", async () => {
  let ready = false
  const server = createServer((_req, res) => {
    if (!ready) {
      res.statusCode = 503
      res.end()
      return
    }
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
  const url = `http://127.0.0.1:${address.port}/mcp`
  try {
    setTimeout(() => {
      ready = true
    }, 30)
    expect(await waitForHttpMcpWithBackoff([url], [20, 40])).toBe(url)
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }
})
