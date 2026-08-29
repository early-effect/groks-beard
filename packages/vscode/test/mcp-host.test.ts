import { expect, it } from "@effect/vitest"
import { chipFromSelection, EditorShowChangesFile } from "@groks-beard/core"
import { dispatchMcpTool } from "@groks-beard/mcp"
import { createMcpToolHost } from "../src/mcp-host.ts"

it("editor_selection prefers the pending Copy Selection buffer", async () => {
  const pending = chipFromSelection({
    absPath: "/repo/a.ts",
    workspaceRoot: "/repo",
    startLine: 4,
    endLine: 8,
  })
  const host = createMcpToolHost({
    workspaceRoot: () => "/repo",
    pendingSelection: () => pending,
    liveSelection: () => ({
      path: "live.ts",
      absPath: "/repo/live.ts",
      startLine: 1,
      endLine: 1,
      text: "live",
    }),
    pendingText: () => "pending-body",
    openFiles: () => ({ tabs: ["/repo/a.ts"], active: "/repo/a.ts" }),
    reveal: async () => undefined,
    beardSnapshot: () => undefined,
    gitHead: () => undefined,
    disk: () => "disk",
    openDiff: async () => undefined,
    notice: () => undefined,
    showChanges: async () => 0,
  })
  const result = await dispatchMcpTool("editor_selection", {}, host) as {
    path: string
    atRef: string
    text: string
  }
  expect(result.path).toBe("a.ts")
  expect(result.atRef).toBe("@a.ts:4-8")
  expect(result.text).toBe("pending-body")
})

it("editor_show_changes is path-only and counts shown files", async () => {
  const shown: Array<unknown> = []
  const host = createMcpToolHost({
    workspaceRoot: () => "/repo",
    pendingSelection: () => undefined,
    liveSelection: () => undefined,
    pendingText: () => undefined,
    openFiles: () => ({ tabs: [] }),
    reveal: async () => undefined,
    beardSnapshot: () => undefined,
    gitHead: () => undefined,
    disk: () => undefined,
    openDiff: async () => undefined,
    notice: () => undefined,
    showChanges: async (title, files) => {
      shown.push({ title, files })
      return files.length
    },
  })
  const result = await dispatchMcpTool("editor_show_changes", {
    title: "TUI",
    files: [new EditorShowChangesFile({ path: "src/a.ts", kind: "modify" })],
  }, host)
  expect(result).toEqual({ ok: true, shown: 1 })
  expect(shown).toEqual([{
    title: "TUI",
    files: [{ path: "/repo/src/a.ts", kind: "modify" }],
  }])
})
