import { client, type ClientConnection, type ClientContext } from "@agentclientprotocol/sdk"
import { Effect } from "effect"
import { AcpError } from "@groks-beard/core"
import { initializeParams, type CapabilityPolicy } from "./capabilities.js"
import { FakeGrokAgent } from "./fake-agent.js"
import { createFramedTransport, type FramedTransport } from "./framed-stream.js"
import { emptySessionState, type SessionState } from "./session-state.js"

export type BeardAcp = {
  readonly connection: ClientConnection
  readonly agent: ClientContext
  readonly transport: FramedTransport
  readonly state: SessionState
}

export const connectBeardAcp = (handlers: {
  readonly fake?: FakeGrokAgent
  readonly onTerminalCreate?: (command: string, state: SessionState) => void
  readonly onPermission?: (params: unknown) => {
    outcome: { outcome: "selected"; optionId: string } | { outcome: "cancelled" }
  }
} = {}): BeardAcp => {
  const state = emptySessionState()
  let feedFromAgent: (bytes: Uint8Array) => void = () => undefined
  const transport = createFramedTransport(state, {
    onOutgoing: (message) => {
      if (handlers.fake === undefined) return
      const bytes = handlers.fake.encodeReplies(message)
      if (bytes.byteLength > 0) feedFromAgent(bytes)
    }
  })
  feedFromAgent = transport.feedFromAgent
  const app = client({ name: "groks-beard" })
    .onRequest("terminal/create", (ctx) => {
      handlers.onTerminalCreate?.(ctx.params.command, state)
      return { terminalId: "beard-term" }
    })
    .onRequest("session/request_permission", (ctx) =>
      handlers.onPermission?.(ctx.params) ?? { outcome: { outcome: "cancelled" as const } }
    )
  const connection = app.connect(transport.stream)
  return { connection, agent: connection.agent, transport, state }
}

export const initializeAgent = (
  agent: ClientContext,
  policy: CapabilityPolicy,
  extensionVersion: string
): Effect.Effect<unknown, AcpError> =>
  Effect.tryPromise({
    try: () => agent.request("initialize", initializeParams(policy, extensionVersion)),
    catch: (cause) =>
      new AcpError({
        method: "initialize",
        message: cause instanceof Error ? cause.message : String(cause)
      })
  })
