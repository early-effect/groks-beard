import { existsSync, readFileSync } from "node:fs"
import { join } from "node:path"

export const MCP_RETRY_DELAYS_MS = [250, 500, 1000, 2000] as const

export type ProjectHttpMcpServer = {
  readonly name: string
  readonly url: string
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null ? value as Record<string, unknown> : undefined

const readJson = (path: string): unknown => {
  try {
    return JSON.parse(readFileSync(path, "utf8"))
  } catch {
    return undefined
  }
}

export const loopbackUrl = (url: string): string => {
  try {
    const parsed = new URL(url)
    if (
      parsed.hostname === "localhost" || parsed.hostname === "[::1]" || parsed.hostname === "::1"
    ) {
      parsed.hostname = "127.0.0.1"
    }
    return parsed.toString()
  } catch {
    return url
  }
}

export const parseProjectHttpMcpServers = (value: unknown): ReadonlyArray<ProjectHttpMcpServer> => {
  const rec = asRecord(value)
  const table = rec !== undefined ? rec.mcpServers ?? rec.servers : undefined
  const servers = asRecord(table)
  if (servers === undefined) return []
  return Object.entries(servers).flatMap(([name, item]) => {
    if (name === "") return []
    const entry = asRecord(item)
    if (entry === undefined || typeof entry.url !== "string" || entry.url === "") return []
    return [{ name, url: entry.url }]
  })
}

export const readProjectHttpMcpServers = (cwd: string): ReadonlyArray<ProjectHttpMcpServer> => {
  const path = join(cwd, ".mcp.json")
  if (!existsSync(path)) return []
  return parseProjectHttpMcpServers(readJson(path)).map((server) => ({
    ...server,
    url: loopbackUrl(server.url),
  }))
}

const unique = (urls: ReadonlyArray<string>): Array<string> => {
  const seen = new Set<string>()
  const out: Array<string> = []
  for (const url of urls) {
    if (url === "" || seen.has(url)) continue
    seen.add(url)
    out.push(url)
  }
  return out
}

export const projectHttpMcpUrlsFor = (
  cwd: string,
  name?: string,
): ReadonlyArray<string> => {
  const declared = readProjectHttpMcpServers(cwd)
  const selected = name === undefined || name === ""
    ? declared
    : declared.filter((server) => server.name === name)
  return unique(selected.map((server) => server.url))
}

export const httpMcpReady = async (url: string): Promise<boolean> => {
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: {
        Accept: "application/json, text/event-stream",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-11-25",
          capabilities: {},
          clientInfo: { name: "groks-beard", version: "0" },
        },
      }),
      signal: AbortSignal.timeout(2_000),
    })
    if (!response.ok) return false
    const json = await response.json() as { result?: unknown }
    return json.result !== undefined
  } catch {
    return false
  }
}

const firstReadyUrl = async (urls: ReadonlyArray<string>): Promise<string | undefined> => {
  for (const url of unique(urls)) {
    if (await httpMcpReady(url)) return url
  }
  return undefined
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

export const waitForHttpMcpWithBackoff = async (
  urls: ReadonlyArray<string>,
  delays: ReadonlyArray<number> = MCP_RETRY_DELAYS_MS,
): Promise<string | undefined> => {
  const first = await firstReadyUrl(urls)
  if (first !== undefined) return first
  for (const delay of delays) {
    await sleep(delay)
    const hit = await firstReadyUrl(urls)
    if (hit !== undefined) return hit
  }
  return undefined
}
