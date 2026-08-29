import {
  EditorOpenDiffArgs,
  EditorOpenFilesArgs,
  EditorRevealArgs,
  EditorShowChangesArgs,
  MCP_TOOL_NAMES,
  type McpToolName,
} from "@groks-beard/core"
import { Schema } from "effect"

export const MCP_ANNOTATIONS = {
  readOnlyHint: true,
  destructiveHint: false,
} as const

export type McpJsonSchema = {
  readonly type: "object"
  readonly properties: Record<string, unknown>
  readonly required?: ReadonlyArray<string>
  readonly additionalProperties?: boolean
}

export type McpToolSpec = {
  readonly name: McpToolName
  readonly description: string
  readonly inputSchema: McpJsonSchema
  readonly annotations: typeof MCP_ANNOTATIONS
}

const emptyObject: McpJsonSchema = { type: "object", properties: {} }

const pathLine: McpJsonSchema = {
  type: "object",
  properties: {
    path: { type: "string", description: "Workspace-relative or absolute path." },
    line: { type: "integer", minimum: 1, description: "1-based line to reveal." },
  },
  required: ["path"],
}

const pathLineStrict: McpJsonSchema = {
  ...pathLine,
  additionalProperties: false,
}

export const MCP_TOOL_SPECS: ReadonlyArray<McpToolSpec> = [
  {
    name: "editor_workspace_root",
    description: "Return the workspace folder open in VS Code or Cursor with Grok's Beard.",
    inputSchema: emptyObject,
    annotations: MCP_ANNOTATIONS,
  },
  {
    name: "editor_selection",
    description:
      "Return the current editor selection or the pending Copy Selection / Add Selection buffer. Prefers an @path:start-end atRef. Text is truncated.",
    inputSchema: emptyObject,
    annotations: MCP_ANNOTATIONS,
  },
  {
    name: "editor_open_files",
    description:
      "List open editor tab paths and the active file. Paths only, no file bodies. Pass cursor from nextCursor to page; results are capped and set truncated when more remain.",
    inputSchema: {
      type: "object",
      properties: {
        cursor: { type: "string", description: "Opaque cursor from a previous nextCursor." },
      },
    },
    annotations: MCP_ANNOTATIONS,
  },
  {
    name: "editor_reveal",
    description: "Reveal a file in the editor, optionally at a 1-based line. Does not write files.",
    inputSchema: pathLine,
    annotations: MCP_ANNOTATIONS,
  },
  {
    name: "editor_open_diff",
    description:
      "Open a native diff for a path using a Beard snapshot, git HEAD, or disk. Paths only. Never send oldText or newText.",
    inputSchema: pathLineStrict,
    annotations: MCP_ANNOTATIONS,
  },
  {
    name: "editor_show_changes",
    description:
      "Show a path-only Grok Changes navigation tree. Does not write files, invent snapshots, or git-snapshot.",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        title: { type: "string" },
        files: {
          type: "array",
          minItems: 1,
          items: {
            type: "object",
            additionalProperties: false,
            properties: {
              path: { type: "string" },
              kind: { type: "string", enum: ["add", "modify", "delete", "move"] },
            },
            required: ["path", "kind"],
          },
        },
      },
      required: ["files"],
    },
    annotations: MCP_ANNOTATIONS,
  },
]

export const mcpToolNames = (): ReadonlyArray<string> => MCP_TOOL_NAMES

export type McpToolHost = {
  readonly workspaceRoot: () => Promise<unknown>
  readonly selection: () => Promise<unknown>
  readonly openFiles: (args: EditorOpenFilesArgs) => Promise<unknown>
  readonly reveal: (args: EditorRevealArgs) => Promise<unknown>
  readonly openDiff: (args: EditorOpenDiffArgs) => Promise<unknown>
  readonly showChanges: (args: EditorShowChangesArgs) => Promise<unknown>
}

const invalidArgs = (tool: string): Error => new Error(`${tool}: invalid arguments`)

export const dispatchMcpTool = (
  tool: McpToolName,
  args: unknown,
  host: McpToolHost,
): Promise<unknown> => {
  const raw = args ?? {}
  switch (tool) {
    case "editor_workspace_root":
      return host.workspaceRoot()
    case "editor_selection":
      return host.selection()
    case "editor_open_files":
      try {
        return host.openFiles(Schema.decodeUnknownSync(EditorOpenFilesArgs)(raw))
      } catch {
        return Promise.reject(invalidArgs(tool))
      }
    case "editor_reveal":
      try {
        return host.reveal(Schema.decodeUnknownSync(EditorRevealArgs)(raw))
      } catch {
        return Promise.reject(invalidArgs(tool))
      }
    case "editor_open_diff":
      try {
        return host.openDiff(
          Schema.decodeUnknownSync(EditorOpenDiffArgs, { onExcessProperty: "error" })(raw),
        )
      } catch {
        return Promise.reject(invalidArgs(tool))
      }
    case "editor_show_changes":
      try {
        return host.showChanges(
          Schema.decodeUnknownSync(EditorShowChangesArgs, { onExcessProperty: "error" })(raw),
        )
      } catch {
        return Promise.reject(invalidArgs(tool))
      }
  }
}

export const isMcpToolName = (name: string): name is McpToolName =>
  (MCP_TOOL_NAMES as ReadonlyArray<string>).includes(name)
