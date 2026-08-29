import { join } from "node:path"

export const MCP_SERVER_TABLE = "mcp_servers.groks-beard"

export const TUI_BRIDGE_REFRESH_MESSAGE =
  "TUI bridge enabled. In a running TUI press r in /mcps, or start a new TUI session. The server does not attach live."

export const projectGrokConfigPath = (workspaceRoot: string): string =>
  join(workspaceRoot, ".grok", "config.toml")

const tomlString = (value: string): string => JSON.stringify(value)

export const renderMcpTable = (
  nodeCommand: string,
  proxyPath: string,
  workspace: string,
): string =>
  `[${MCP_SERVER_TABLE}]\ncommand = ${tomlString(nodeCommand)}\nargs = [${
    tomlString(proxyPath)
  }, "--workspace", ${tomlString(workspace)}]\n`

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")

const tableRange = (text: string, header: string): { start: number; end: number } | undefined => {
  const re = new RegExp(`^\\[${escapeRegExp(header)}\\][ \\t]*\\r?\\n?`, "m")
  const match = re.exec(text)
  if (match === null) return undefined
  const start = match.index
  const after = start + match[0].length
  const rest = text.slice(after)
  const next = /^[ \t]*\[/m.exec(rest)
  const end = next === null ? text.length : after + next.index
  return { start, end }
}

export const mergeMcpTable = (existing: string, table: string): string => {
  const range = tableRange(existing, MCP_SERVER_TABLE)
  if (range === undefined) {
    const trimmed = existing.replace(/\s*$/, "")
    return trimmed === "" ? table : `${trimmed}\n\n${table}`
  }
  const before = existing.slice(0, range.start).replace(/\s*$/, "")
  const after = existing.slice(range.end).replace(/^\s*/, "")
  const parts = [before, table.replace(/\s*$/, ""), after].filter((part) => part.length > 0)
  return `${parts.join("\n\n")}\n`
}

export const removeMcpTable = (existing: string): string => {
  const range = tableRange(existing, MCP_SERVER_TABLE)
  if (range === undefined) return existing
  const before = existing.slice(0, range.start).replace(/\s*$/, "")
  const after = existing.slice(range.end).replace(/^\s*/, "")
  if (before === "") return after === "" ? "" : `${after}${after.endsWith("\n") ? "" : "\n"}`
  if (after === "") return `${before}\n`
  return `${before}\n\n${after}${after.endsWith("\n") ? "" : "\n"}`
}
