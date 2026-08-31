import { expect, it } from "@effect/vitest"
import {
  firstJsonValue,
  folderTrustPromptMessage,
  grokMcpCliArgs,
  mergeDoctorServer,
  overlayMcpTools,
  parseDoctorJson,
  parseMcpListServers,
  reportNeedsFolderTrust,
  toolCountFromChecks,
  untrustedServerNames,
} from "../src/grok-mcp.ts"

it("parses grok mcp doctor JSON including failed auth servers", () => {
  const report = parseDoctorJson({
    sources: [],
    servers: [
      {
        name: "metals",
        transport: "stdio",
        source: "~/.grok/config.toml",
        healthy: true,
        checks: [
          { label: "command found", passed: true, detail: "/usr/bin/metals-mcp" },
          { label: "handshake OK", passed: true, detail: "protocol 2025-11-25" },
          { label: "12 tools discovered", passed: true, detail: "" },
        ],
      },
      {
        name: "atlassian",
        transport: "http",
        source: "~/.claude.json",
        healthy: false,
        checks: [
          {
            label: "handshake failed",
            passed: false,
            detail: "OAuth authorization required",
            hint: "check server logs",
          },
        ],
      },
    ],
    healthy_count: 1,
    failing_count: 1,
  })
  expect(report.healthyCount).toBe(1)
  expect(report.failingCount).toBe(1)
  expect(report.servers[0]).toMatchObject({
    name: "metals",
    transport: "stdio",
    toolCount: 12,
    healthy: true,
  })
  expect(report.servers[1]?.healthy).toBe(false)
  expect(report.servers[1]?.checks[0]?.detail).toContain("OAuth")
})

it("reads tool counts from doctor check labels", () => {
  expect(toolCountFromChecks([{ label: "24 tools discovered", passed: true }])).toBe(24)
  expect(toolCountFromChecks([{ label: "1 tool discovered", passed: true }])).toBe(1)
  expect(toolCountFromChecks([{ label: "handshake OK", passed: true }])).toBeUndefined()
})

it("skips log noise before the JSON payload", () => {
  expect(firstJsonValue('ERROR worker quit\n{"servers":[]}')).toEqual({ servers: [] })
})

it("detects the folder-untrusted doctor check", () => {
  const report = parseDoctorJson({
    servers: [
      {
        name: "metals",
        transport: "http",
        source: ".mcp.json",
        healthy: false,
        checks: [
          {
            label: "folder untrusted",
            passed: false,
            detail: "repo-local (project-scoped) server not started for an untrusted folder",
            hint: "re-run with --trust to allow repo-local servers",
          },
        ],
      },
    ],
  })
  expect(reportNeedsFolderTrust(report)).toBe(true)
  expect(untrustedServerNames(report)).toEqual(["metals"])
  expect(folderTrustPromptMessage("/tmp/saferis", ["metals"])).toContain(
    "the repo-local MCP server metals",
  )
  expect(reportNeedsFolderTrust({
    servers: [{
      name: "playwright",
      transport: "stdio",
      source: "~/.claude.json",
      healthy: true,
      checks: [{ label: "handshake OK", passed: true }],
    }],
    healthyCount: 1,
    failingCount: 0,
  })).toBe(false)
})

it("parses a single-server doctor payload", () => {
  const report = parseDoctorJson({
    name: "metals",
    transport: "http",
    source: ".mcp.json",
    healthy: true,
    checks: [{ label: "17 tools discovered", passed: true }],
  })
  expect(report.servers).toHaveLength(1)
  expect(report.servers[0]?.name).toBe("metals")
  expect(report.servers[0]?.toolCount).toBe(17)
  expect(report.healthyCount).toBe(1)
})

it("replaces one server in a doctor report without dropping the others", () => {
  const merged = mergeDoctorServer({
    servers: [
      {
        name: "metals",
        transport: "http",
        source: ".mcp.json",
        healthy: false,
        checks: [{ label: "handshake failed", passed: false }],
      },
      {
        name: "playwright",
        transport: "stdio",
        source: "~/.claude.json",
        healthy: true,
        checks: [{ label: "handshake OK", passed: true }],
      },
    ],
    healthyCount: 1,
    failingCount: 1,
  }, {
    name: "metals",
    transport: "http",
    source: ".mcp.json",
    healthy: true,
    toolCount: 17,
    checks: [{ label: "17 tools discovered", passed: true }],
  })
  expect(merged.healthyCount).toBe(2)
  expect(merged.failingCount).toBe(0)
  expect(merged.servers[0]).toMatchObject({ name: "metals", healthy: true, toolCount: 17 })
  expect(merged.servers[1]?.name).toBe("playwright")
})

it("reads MCP tool lists from Grok _x.ai/mcp/list, defaulting enabled", () => {
  const listed = parseMcpListServers({
    result: {
      servers: [
        {
          name: "metals",
          session: {
            tools: [
              { name: "compile-file", description: "Compile a chosen Scala file", enabled: true },
              { name: "test", enabled: false },
            ],
          },
        },
        { name: "managed_gateway:tasks", session: { tools: [{ name: "tasks__create" }] } },
        { name: "atlassian", session: { status: "unavailable" } },
      ],
    },
  })
  expect(listed).toEqual([
    {
      name: "metals",
      tools: [
        { name: "compile-file", enabled: true, description: "Compile a chosen Scala file" },
        { name: "test", enabled: false },
      ],
    },
    {
      name: "managed_gateway:tasks",
      tools: [{ name: "tasks__create", enabled: true }],
    },
  ])
})

it("overlays listed tools onto matching doctor servers only", () => {
  const report = parseDoctorJson({
    servers: [
      {
        name: "metals",
        transport: "http",
        source: ".mcp.json",
        healthy: true,
        checks: [{ label: "17 tools discovered", passed: true }],
      },
      {
        name: "playwright",
        transport: "stdio",
        source: "~/.claude.json",
        healthy: true,
        checks: [{ label: "24 tools discovered", passed: true }],
      },
    ],
  })
  const overlaid = overlayMcpTools(report, [{
    name: "metals",
    tools: [{ name: "compile-file", enabled: true }, { name: "test", enabled: false }],
  }])
  expect(overlaid.servers[0]?.tools).toEqual([
    { name: "compile-file", enabled: true },
    { name: "test", enabled: false },
  ])
  expect(overlaid.servers[0]?.toolCount).toBe(2)
  expect(overlaid.servers[1]?.tools).toBeUndefined()
  expect(overlaid.servers[1]?.toolCount).toBe(24)
})

it("keeps previous tool rows when a doctor refresh omits them", () => {
  const merged = mergeDoctorServer({
    servers: [{
      name: "metals",
      transport: "http",
      source: ".mcp.json",
      healthy: true,
      toolCount: 1,
      tools: [{ name: "compile-file", enabled: true }],
      checks: [{ label: "1 tool discovered", passed: true }],
    }],
    healthyCount: 1,
    failingCount: 0,
  }, {
    name: "metals",
    transport: "http",
    source: ".mcp.json",
    healthy: true,
    toolCount: 1,
    checks: [{ label: "1 tool discovered", passed: true }],
  })
  expect(merged.servers[0]?.tools).toEqual([{ name: "compile-file", enabled: true }])
})

it("prefixes grok mcp with --trust only after folder trust is granted", () => {
  expect([...grokMcpCliArgs(["doctor", "--json"])]).toEqual(["mcp", "doctor", "--json"])
  expect([...grokMcpCliArgs(["doctor", "--json"], true)]).toEqual([
    "--trust",
    "mcp",
    "doctor",
    "--json",
  ])
})
