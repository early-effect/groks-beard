import { expect, it } from "@effect/vitest"
import {
  CliNotFound,
  MCP_EDITOR_DOWN_MESSAGE,
  McpEditorDown,
  MethodNotFound,
  NodeNotFound,
} from "../src/errors.ts"

it("CliNotFound is tagged", () => {
  const error = new CliNotFound({ searched: ["/bin/grok"] })
  expect(error._tag).toBe("CliNotFound")
  expect(error.searched).toEqual(["/bin/grok"])
})

it("NodeNotFound is tagged", () => {
  const error = new NodeNotFound({ searched: ["/usr/bin/node"] })
  expect(error._tag).toBe("NodeNotFound")
  expect(error.searched).toEqual(["/usr/bin/node"])
})

it("McpEditorDown tells the user to enable the TUI bridge", () => {
  const error = new McpEditorDown({ workspace: "/repo" })
  expect(error._tag).toBe("McpEditorDown")
  expect(error.workspace).toBe("/repo")
  expect(MCP_EDITOR_DOWN_MESSAGE).toContain("Enable TUI Bridge")
})

it("MethodNotFound maps to JSON-RPC -32601", () => {
  const error = new MethodNotFound({ method: "_x.ai/interject" })
  expect(error._tag).toBe("MethodNotFound")
  expect(error.jsonRpcCode).toBe(-32601)
})
