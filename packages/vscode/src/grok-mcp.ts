export type McpCheck = {
  readonly label: string
  readonly passed: boolean
  readonly detail?: string
  readonly hint?: string
}

export type McpToolStatus = {
  readonly name: string
  readonly enabled: boolean
  readonly description?: string
}

export type McpServerStatus = {
  readonly name: string
  readonly transport: string
  readonly source: string
  readonly healthy: boolean
  readonly toolCount?: number
  readonly tools?: ReadonlyArray<McpToolStatus>
  readonly checks: ReadonlyArray<McpCheck>
}

export type McpDoctorReport = {
  readonly servers: ReadonlyArray<McpServerStatus>
  readonly healthyCount: number
  readonly failingCount: number
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null ? value as Record<string, unknown> : undefined

const clip = (value: string, max = 240): string =>
  value.length <= max ? value : `${value.slice(0, max - 1)}…`

export const firstJsonValue = (stdout: string): unknown => {
  const start = stdout.search(/[\[{]/)
  if (start < 0) throw new Error("grok mcp produced no JSON")
  return JSON.parse(stdout.slice(start))
}

export const toolCountFromChecks = (checks: ReadonlyArray<McpCheck>): number | undefined => {
  for (const check of checks) {
    const match = /^(\d+)\s+tools?\s+discovered$/i.exec(check.label)
    if (match?.[1] !== undefined) return Number(match[1])
  }
  return undefined
}

export const folderUntrustedCheck = (check: Pick<McpCheck, "label" | "passed">): boolean =>
  !check.passed && /folder untrusted/i.test(check.label)

export const reportNeedsFolderTrust = (report: Pick<McpDoctorReport, "servers">): boolean =>
  report.servers.some((server) => server.checks.some(folderUntrustedCheck))

export const untrustedServerNames = (
  report: Pick<McpDoctorReport, "servers">,
): ReadonlyArray<string> =>
  report.servers.filter((server) => server.checks.some(folderUntrustedCheck)).map((server) =>
    server.name
  )

export const folderTrustPromptMessage = (
  root: string,
  names: ReadonlyArray<string>,
): string => {
  const where = `in ${root}`
  const extra = "Trust this folder to start them? This also allows project hooks and LSP for Grok."
  if (names.length === 0) {
    return `Grok blocked repo-local MCP ${where}. ${extra}`
  }
  if (names.length === 1) {
    return `Grok blocked the repo-local MCP server ${names[0]} ${where}. ${extra}`
  }
  return `Grok blocked repo-local MCP servers ${names.join(", ")} ${where}. ${extra}`
}

export const folderTrustDismissKey = (root: string): string => `folderTrustDismissed:${root}`

export const grokMcpCliArgs = (
  mcpArgs: ReadonlyArray<string>,
  trustFolder = false,
): ReadonlyArray<string> => trustFolder ? ["--trust", "mcp", ...mcpArgs] : ["mcp", ...mcpArgs]

const parseCheck = (value: unknown): McpCheck | undefined => {
  const rec = asRecord(value)
  if (rec === undefined || typeof rec.label !== "string" || rec.label === "") return undefined
  const detail = typeof rec.detail === "string" && rec.detail !== "" ? clip(rec.detail) : undefined
  const hint = typeof rec.hint === "string" && rec.hint !== "" ? clip(rec.hint) : undefined
  return {
    label: rec.label,
    passed: rec.passed === true,
    ...(detail !== undefined ? { detail } : {}),
    ...(hint !== undefined ? { hint } : {}),
  }
}

export const unwrapMcpPayload = (value: unknown): unknown => {
  let current = value
  for (let i = 0; i < 3; i++) {
    const rec = asRecord(current)
    if (rec === undefined) return current
    if (Array.isArray(rec.servers) || typeof rec.name === "string") return current
    if (rec.result === undefined) return current
    current = rec.result
  }
  return current
}

export const parseMcpListTools = (value: unknown): ReadonlyArray<McpToolStatus> => {
  if (!Array.isArray(value)) return []
  return value.flatMap((item): Array<McpToolStatus> => {
    if (typeof item === "string" && item !== "") return [{ name: item, enabled: true }]
    const rec = asRecord(item)
    if (rec === undefined || typeof rec.name !== "string" || rec.name === "") return []
    const description = typeof rec.description === "string" && rec.description !== ""
      ? clip(rec.description)
      : undefined
    return [{
      name: rec.name,
      enabled: rec.enabled !== false,
      ...(description !== undefined ? { description } : {}),
    }]
  })
}

export const parseMcpListServers = (
  value: unknown,
): ReadonlyArray<{ name: string; tools: ReadonlyArray<McpToolStatus> }> => {
  const rec = asRecord(unwrapMcpPayload(value))
  const servers = rec !== undefined && Array.isArray(rec.servers) ? rec.servers : []
  return servers.flatMap((item) => {
    const server = asRecord(item)
    if (server === undefined || typeof server.name !== "string" || server.name === "") return []
    const session = asRecord(server.session)
    const tools = parseMcpListTools(session?.tools ?? server.tools)
    if (tools.length === 0) return []
    return [{ name: server.name, tools }]
  })
}

export const overlayMcpTools = (
  report: McpDoctorReport,
  listed: ReadonlyArray<{ name: string; tools: ReadonlyArray<McpToolStatus> }>,
): McpDoctorReport => {
  if (listed.length === 0) return report
  const byName = new Map(listed.map((row) => [row.name, row.tools]))
  return {
    ...report,
    servers: report.servers.map((server) => {
      const tools = byName.get(server.name)
      if (tools === undefined) return server
      return { ...server, tools, toolCount: tools.length }
    }),
  }
}

const parseServer = (value: unknown): McpServerStatus | undefined => {
  const rec = asRecord(value)
  if (rec === undefined || typeof rec.name !== "string" || rec.name === "") return undefined
  const checks = Array.isArray(rec.checks)
    ? rec.checks.flatMap((item) => {
      const check = parseCheck(item)
      return check === undefined ? [] : [check]
    })
    : []
  const tools = parseMcpListTools(rec.tools)
  const toolCount = tools.length > 0 ? tools.length : toolCountFromChecks(checks)
  return {
    name: rec.name,
    transport: typeof rec.transport === "string" && rec.transport !== "" ? rec.transport : "stdio",
    source: typeof rec.source === "string" && rec.source !== "" ? rec.source : "unknown",
    healthy: rec.healthy === true,
    ...(toolCount !== undefined ? { toolCount } : {}),
    ...(tools.length > 0 ? { tools } : {}),
    checks,
  }
}

export const parseDoctorJson = (value: unknown): McpDoctorReport => {
  const rec = asRecord(value)
  const fromList = rec !== undefined && Array.isArray(rec.servers)
    ? rec.servers.flatMap((item) => {
      const server = parseServer(item)
      return server === undefined ? [] : [server]
    })
    : []
  const servers = fromList.length > 0
    ? fromList
    : (() => {
      const one = rec === undefined ? undefined : parseServer(rec)
      return one === undefined ? [] : [one]
    })()
  const healthyCount = servers.filter((server) => server.healthy).length
  return {
    servers,
    healthyCount,
    failingCount: servers.length - healthyCount,
  }
}

export const mergeDoctorServer = (
  report: McpDoctorReport,
  server: McpServerStatus,
): McpDoctorReport => {
  const servers = report.servers.some((row) => row.name === server.name)
    ? report.servers.map((row) => {
      if (row.name !== server.name) return row
      return {
        ...server,
        ...(server.tools !== undefined ? { tools: server.tools } : row.tools !== undefined
          ? { tools: row.tools }
          : {}),
      }
    })
    : [...report.servers, server]
  const healthyCount = servers.filter((row) => row.healthy).length
  return {
    servers,
    healthyCount,
    failingCount: servers.length - healthyCount,
  }
}
