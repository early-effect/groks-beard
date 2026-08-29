import { MCP_EDITOR_DOWN_MESSAGE, McpEditorDown } from "@groks-beard/core"
import type { Readable, Writable } from "node:stream"
import { callBridge, type ConnectBridge, probeBridge } from "./bridge-client.js"
import { encodeNdjson, splitNdjson } from "./ndjson.js"
import { socketAddress } from "./socket-address.js"
import { isMcpToolName, MCP_TOOL_SPECS } from "./tools.js"

export const MCP_PROTOCOL_VERSION = "2025-03-26"

const SUPPORTED_PROTOCOL_VERSIONS = [
  "2024-11-05",
  "2025-03-26",
  "2025-06-18",
] as const

export const MCP_SERVER_INFO = {
  name: "groks-beard",
  version: "0.0.0",
} as const

export type McpProxyIo = {
  readonly workspace: string
  readonly stdin: Readable
  readonly stdout: Writable
  readonly stderr: Writable
  readonly connect?: ConnectBridge
  readonly win?: boolean
  readonly runtimeDir?: string
  readonly tmpdir?: string
}

type JsonRpc = {
  readonly jsonrpc?: string
  readonly id?: string | number
  readonly method?: string
  readonly params?: unknown
}

const jsonRpc = (id: string | number, result: unknown) => ({
  jsonrpc: "2.0",
  id,
  result,
})

const jsonRpcError = (id: string | number, code: number, message: string) => ({
  jsonrpc: "2.0",
  id,
  error: { code, message },
})

const toolText = (value: unknown, isError = false) => ({
  content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value) }],
  isError,
})

const protocolVersionOf = (params: unknown): string => {
  if (typeof params === "object" && params !== null && "protocolVersion" in params) {
    const version = (params as { protocolVersion: unknown }).protocolVersion
    if (
      typeof version === "string"
      && (SUPPORTED_PROTOCOL_VERSIONS as ReadonlyArray<string>).includes(version)
    ) {
      return version
    }
  }
  return MCP_PROTOCOL_VERSION
}

const toolCallParams = (params: unknown): { name: string; arguments: unknown } => {
  if (typeof params !== "object" || params === null) return { name: "", arguments: {} }
  const name = (params as { name?: unknown }).name
  const args = (params as { arguments?: unknown }).arguments
  return {
    name: typeof name === "string" ? name : "",
    arguments: args ?? {},
  }
}

export const handleMcpRequest = async (
  message: JsonRpc,
  ctx: {
    readonly address: string
    readonly workspace: string
    readonly connect?: ConnectBridge
    nextId: number
  },
): Promise<unknown | undefined> => {
  const method = message.method
  if (method === undefined) return undefined
  if (message.id === undefined) return undefined
  const id = message.id
  if (method === "initialize") {
    return jsonRpc(id, {
      protocolVersion: protocolVersionOf(message.params),
      capabilities: { tools: {} },
      serverInfo: MCP_SERVER_INFO,
    })
  }
  if (method === "ping") return jsonRpc(id, {})
  if (method === "tools/list") {
    return jsonRpc(id, {
      tools: MCP_TOOL_SPECS.map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema,
        annotations: tool.annotations,
      })),
    })
  }
  if (method === "tools/call") {
    const { name, arguments: args } = toolCallParams(message.params)
    if (!isMcpToolName(name)) return jsonRpcError(id, -32602, `Unknown tool: ${name}`)
    ctx.nextId += 1
    try {
      const result = await callBridge(
        ctx.address,
        ctx.workspace,
        {
          id: String(ctx.nextId),
          tool: name,
          ...(args !== undefined ? { args } : {}),
        },
        ctx.connect,
      )
      return jsonRpc(id, toolText(result))
    } catch (cause) {
      if (cause instanceof McpEditorDown) throw cause
      const messageText = cause instanceof Error ? cause.message : String(cause)
      return jsonRpc(id, toolText(messageText, true))
    }
  }
  return jsonRpcError(id, -32601, `Method not found: ${method}`)
}

export const runMcpProxy = (io: McpProxyIo): Promise<number> =>
  new Promise((resolve) => {
    const address = socketAddress({
      workspace: io.workspace,
      ...(io.win !== undefined ? { win: io.win } : {}),
      ...(io.runtimeDir !== undefined ? { runtimeDir: io.runtimeDir } : {}),
      ...(io.tmpdir !== undefined ? { tmpdir: io.tmpdir } : {}),
    })
    const connect = io.connect
    const failDown = () => {
      io.stderr.write(`${MCP_EDITOR_DOWN_MESSAGE}\n`)
      resolve(1)
    }
    void probeBridge(address, io.workspace, connect).then(
      () => {
        const ctx = {
          address,
          workspace: io.workspace,
          nextId: 0,
          ...(connect !== undefined ? { connect } : {}),
        }
        let buffer = ""
        let settled = false
        const finish = (code: number) => {
          if (settled) return
          settled = true
          resolve(code)
        }
        io.stdin.setEncoding("utf8")
        io.stdin.on("data", (chunk: string | Buffer) => {
          const split = splitNdjson(
            buffer,
            typeof chunk === "string" ? chunk : chunk.toString("utf8"),
          )
          buffer = split.rest
          for (const line of split.lines) {
            let parsed: unknown
            try {
              parsed = JSON.parse(line)
            } catch {
              continue
            }
            const msg = parsed as JsonRpc
            if (
              msg.method === "notifications/initialized" || msg.method === "notifications/cancelled"
            ) {
              continue
            }
            void handleMcpRequest(msg, ctx).then((response) => {
              if (response === undefined) return
              io.stdout.write(encodeNdjson(response))
            }).catch((cause: unknown) => {
              if (cause instanceof McpEditorDown) {
                failDown()
                return
              }
              finish(1)
            })
          }
        })
        io.stdin.on("end", () => finish(0))
        io.stdin.on("error", () => finish(1))
      },
      failDown,
    )
  })

export const parseWorkspaceArg = (argv: ReadonlyArray<string>): string | undefined => {
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === "--workspace") return argv[i + 1]
    if (arg !== undefined && arg.startsWith("--workspace=")) return arg.slice("--workspace=".length)
  }
  return undefined
}
