import { expect, it } from "@effect/vitest"
import { BridgeRequest, decodeBridgeRequest, encodeBridgeRequest } from "../src/bridge-protocol.ts"
import { dispatchMcpTool, MCP_TOOL_SPECS, mcpToolNames } from "../src/tools.ts"

it("round-trips a bridge request through Schema", () => {
  const encoded = encodeBridgeRequest(
    new BridgeRequest({
      id: "1",
      tool: "editor_workspace_root",
      args: {},
    }),
  )
  expect(decodeBridgeRequest(encoded).tool).toBe("editor_workspace_root")
})

it("lists only read-only path tools with MCP annotations", () => {
  const names = mcpToolNames()
  expect(names).toEqual([
    "editor_workspace_root",
    "editor_selection",
    "editor_open_files",
    "editor_reveal",
    "editor_open_diff",
    "editor_show_changes",
  ])
  expect(
    names.some((name) =>
      name.includes("write") || name.includes("apply") || name.includes("terminal")
    ),
  )
    .toBe(false)
  for (const spec of MCP_TOOL_SPECS) {
    expect(spec.annotations.readOnlyHint).toBe(true)
    expect(spec.annotations.destructiveHint).toBe(false)
    expect(spec.description.length).toBeGreaterThan(0)
  }
})

it("dispatches editor_show_changes with a non-empty files list", async () => {
  const shown: Array<unknown> = []
  const result = await dispatchMcpTool("editor_show_changes", {
    title: "TUI edits",
    files: [{ path: "src/a.ts", kind: "modify" }],
  }, {
    workspaceRoot: async () => ({ root: "/repo" }),
    selection: async () => ({ truncated: false }),
    openFiles: async () => ({ tabs: [] }),
    reveal: async () => ({ ok: true }),
    openDiff: async () => ({ ok: true }),
    showChanges: async (args) => {
      shown.push(args)
      return { ok: true, shown: args.files.length }
    },
  })
  expect(result).toEqual({ ok: true, shown: 1 })
  expect(shown).toHaveLength(1)
})

it("rejects editor_open_diff without a path", async () => {
  await expect(dispatchMcpTool("editor_open_diff", {}, {
    workspaceRoot: async () => ({ root: "/repo" }),
    selection: async () => ({ truncated: false }),
    openFiles: async () => ({ tabs: [] }),
    reveal: async () => ({ ok: true }),
    openDiff: async () => ({ ok: true }),
    showChanges: async () => ({ ok: true, shown: 1 }),
  })).rejects.toThrow(/invalid arguments/)
})
