import type { AnyMessage, AnyRequest } from "@agentclientprotocol/sdk"
import { isJsonRpcRequest } from "./jsonrpc-line.js"
import { JSON_RPC_METHOD_NOT_FOUND } from "./methods.js"
import { encodeNdjsonChunk } from "./ndjson.js"

export type FakeAgentOptions = {
  readonly sessionId?: string
  readonly lockLoad?: boolean
  readonly pairSetModeWithTerminal?: boolean
}

export class FakeGrokAgent {
  readonly sessionId: string
  readonly lockLoad: boolean
  readonly pairSetModeWithTerminal: boolean
  readonly updates: Array<AnyMessage> = []

  constructor(options: FakeAgentOptions = {}) {
    this.sessionId = options.sessionId ?? "sess_test"
    this.lockLoad = options.lockLoad ?? false
    this.pairSetModeWithTerminal = options.pairSetModeWithTerminal ?? false
  }

  repliesFor(message: AnyMessage): ReadonlyArray<AnyMessage> {
    if (!isJsonRpcRequest(message)) return []
    return this.repliesForRequest(message)
  }

  encodeReplies(message: AnyMessage): Uint8Array {
    const replies = this.repliesFor(message)
    if (replies.length === 0) return new Uint8Array()
    return encodeNdjsonChunk(replies)
  }

  private repliesForRequest(request: AnyRequest): ReadonlyArray<AnyMessage> {
    switch (request.method) {
      case "initialize":
        return [ok(request.id, {
          protocolVersion: 1,
          agentCapabilities: { loadSession: true },
        })]
      case "session/new":
        return [
          notify("session/update", {
            sessionId: this.sessionId,
            update: {
              sessionUpdate: "available_commands_update",
              availableCommands: [
                { name: "compact", description: "Compact context" },
                { name: "always-approve", description: "Skip permission prompts" },
              ],
            },
          }),
          ok(request.id, {
            sessionId: this.sessionId,
            modes: {
              currentModeId: "normal",
              availableModes: [
                { id: "normal", name: "Normal" },
                { id: "plan", name: "Plan" },
                { id: "always-approve", name: "Always-approve" },
              ],
            },
          }),
        ]
      case "session/load":
        if (this.lockLoad) {
          return [fail(request.id, JSON_RPC_METHOD_NOT_FOUND, "session locked")]
        }
        return [ok(request.id, { sessionId: this.sessionId })]
      case "session/set_mode": {
        const result = ok(request.id, {})
        if (!this.pairSetModeWithTerminal) return [result]
        return [
          result,
          requestMsg("terminal/create", {
            sessionId: this.sessionId,
            command: "rm",
            args: ["-rf", "/tmp/beard-probe"],
          }, "term-1"),
        ]
      }
      case "session/prompt":
        return [
          notify("session/update", {
            sessionId: this.sessionId,
            update: {
              sessionUpdate: "agent_thought_chunk",
              content: { type: "text", text: "Considering the selection.\n" },
            },
          }),
          notify("session/update", {
            sessionId: this.sessionId,
            update: {
              sessionUpdate: "agent_thought_chunk",
              content: { type: "text", text: "Then I'll answer.\n" },
            },
          }),
          notify("session/update", {
            sessionId: this.sessionId,
            update: {
              sessionUpdate: "agent_message_chunk",
              content: { type: "text", text: "hello" },
            },
          }),
          notify("session/update", {
            sessionId: this.sessionId,
            update: {
              sessionUpdate: "tool_call",
              toolCallId: "call_1",
              title: "Edit",
              kind: "edit",
              status: "pending",
              content: [{
                type: "diff",
                path: "/tmp/file.ts",
                oldText: "old",
                newText: "new",
              }],
            },
          }),
          ok(request.id, { stopReason: "end_turn" }),
        ]
      case "session/request_permission":
        return [ok(request.id, { outcome: { outcome: "selected", optionId: "allow-once" } })]
      default:
        return [fail(request.id, JSON_RPC_METHOD_NOT_FOUND, `Method not found: ${request.method}`)]
    }
  }
}

export const requestPermissionEdit = (
  sessionId: string,
  id: string | number = "perm-1",
): AnyMessage =>
  requestMsg("session/request_permission", {
    sessionId,
    toolCall: {
      toolCallId: "call_1",
      title: "Edit",
      kind: "edit",
      content: [{ type: "diff", path: "/tmp/file.ts", oldText: "old", newText: "new" }],
    },
    options: [
      { optionId: "allow-once", name: "Allow once", kind: "allow_once" },
      { optionId: "reject-once", name: "Reject", kind: "reject_once" },
    ],
  }, id)

const ok = (id: string | number | null, result: unknown): AnyMessage => ({
  jsonrpc: "2.0",
  id,
  result,
})

const fail = (id: string | number | null, code: number, message: string): AnyMessage => ({
  jsonrpc: "2.0",
  id,
  error: { code, message },
})

const notify = (method: string, params: unknown): AnyMessage => ({
  jsonrpc: "2.0",
  method,
  params,
})

const requestMsg = (method: string, params: unknown, id: string | number): AnyMessage => ({
  jsonrpc: "2.0",
  id,
  method,
  params,
})
