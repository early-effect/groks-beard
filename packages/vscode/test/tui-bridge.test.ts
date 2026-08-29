import { expect, it } from "@effect/vitest"
import { callBridge, socketAddress } from "@groks-beard/mcp"
import { existsSync, mkdtempSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { TuiBridge } from "../src/tui-bridge.ts"

it("does not listen when the workspaceState flag is off", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-tui-off-"))
  const address = socketAddress({ workspace })
  const bridge = new TuiBridge({
    getEnabled: () => false,
    workspace: () => workspace,
    handle: async () => ({ root: workspace }),
    log: () => undefined,
  })
  await bridge.sync()
  expect(bridge.listening).toBe(false)
  expect(existsSync(address)).toBe(false)
})

it("binds the per-workspace socket when the bridge flag is on", async () => {
  const workspace = mkdtempSync(join(tmpdir(), "beard-tui-on-"))
  const address = socketAddress({ workspace })
  let enabled = true
  const bridge = new TuiBridge({
    getEnabled: () => enabled,
    workspace: () => workspace,
    handle: async (request) => {
      if (request.tool === "editor_workspace_root") return { root: workspace }
      return { ok: true }
    },
    log: () => undefined,
  })
  try {
    await bridge.sync()
    expect(bridge.listening).toBe(true)
    const result = await callBridge(address, workspace, {
      id: "1",
      tool: "editor_workspace_root",
    })
    expect(result).toEqual({ root: workspace })
    enabled = false
    await bridge.sync()
    expect(bridge.listening).toBe(false)
    expect(existsSync(address)).toBe(false)
  } finally {
    await bridge.unbind()
  }
})
