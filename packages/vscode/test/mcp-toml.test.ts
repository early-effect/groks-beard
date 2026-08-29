import { expect, it } from "@effect/vitest"
import { homedir } from "node:os"
import {
  mergeMcpTable,
  projectGrokConfigPath,
  removeMcpTable,
  renderMcpTable,
} from "../src/mcp-toml.ts"

it("writes only the project .grok/config.toml path", () => {
  expect(projectGrokConfigPath("/repo")).toBe("/repo/.grok/config.toml")
  expect(projectGrokConfigPath("/repo")).not.toContain(homedir())
})

it("appends the groks-beard table while preserving comments", () => {
  const existing = '# team mcp\n[mcp_servers.other]\ncommand = "npx"\n'
  const table = renderMcpTable("/usr/bin/node", "/ext/dist/mcp-proxy.js", "/repo")
  const merged = mergeMcpTable(existing, table)
  expect(merged.startsWith("# team mcp")).toBe(true)
  expect(merged).toContain("[mcp_servers.other]")
  expect(merged).toContain("[mcp_servers.groks-beard]")
  expect(merged).toContain('"/usr/bin/node"')
  expect(merged).toContain('"--workspace"')
  expect(merged).not.toMatch(/execPath/)
})

it("replaces an existing groks-beard table without dropping later tables", () => {
  const existing = '[mcp_servers.groks-beard]\ncommand = "old"\n\n[plugins]\nfoo = true\n'
  const table = renderMcpTable("/bin/node", "/proxy.js", "/ws")
  const merged = mergeMcpTable(existing, table)
  expect(merged).toContain('command = "/bin/node"')
  expect(merged).not.toContain('command = "old"')
  expect(merged).toContain("[plugins]")
})

it("removes only the groks-beard table", () => {
  const existing =
    '# keep\n[mcp_servers.groks-beard]\ncommand = "x"\n\n[mcp_servers.other]\ncommand = "y"\n'
  const next = removeMcpTable(existing)
  expect(next).toContain("# keep")
  expect(next).toContain("[mcp_servers.other]")
  expect(next).not.toContain("groks-beard")
})
