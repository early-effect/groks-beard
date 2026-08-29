import {
  type AnyMessage,
  client,
  type ClientConnection,
  type ClientContext,
} from "@agentclientprotocol/sdk"
import { AcpError } from "@groks-beard/core"
import { Effect } from "effect"
import { type CapabilityPolicy, initializeParams } from "./capabilities.js"
import { FakeGrokAgent } from "./fake-agent.js"
import { createFramedTransport, type FramedTransport } from "./framed-stream.js"
import { emptySessionState, type SessionState } from "./session-state.js"

export type PermissionOutcome = {
  outcome: { outcome: "selected"; optionId: string } | { outcome: "cancelled" }
}

export type BeardClientHandlers = {
  readonly fake?: FakeGrokAgent
  readonly onOutgoing?: (message: AnyMessage) => void
  readonly onTerminalCreate?: (command: string, state: SessionState) => void
  readonly onPermission?: (
    params: unknown,
    requestId: string,
  ) => PermissionOutcome | Promise<PermissionOutcome>
  readonly onSessionUpdate?: (params: unknown) => void
  readonly onExitPlanMode?: (
    params: unknown,
    requestId: string,
  ) =>
    | { outcome: "approved" | "cancelled" | "abandoned"; comment?: string }
    | Promise<{ outcome: "approved" | "cancelled" | "abandoned"; comment?: string }>
  readonly onAskUserQuestion?: (
    params: unknown,
    requestId: string,
  ) => unknown | Promise<unknown>
  readonly onElicit?: (
    params: unknown,
    requestId: string,
  ) =>
    | { action: "accept" | "decline" | "cancel" }
    | Promise<{ action: "accept" | "decline" | "cancel" }>
}

export type BeardAcp = {
  readonly connection: ClientConnection
  readonly agent: ClientContext
  readonly transport: FramedTransport
  readonly state: SessionState
}

export const connectBeardAcp = (handlers: BeardClientHandlers = {}): BeardAcp => {
  const state = emptySessionState()
  let feedFromAgent: (bytes: Uint8Array) => void = () => undefined
  const transport = createFramedTransport(state, {
    onOutgoing: (message) => {
      handlers.onOutgoing?.(message)
      if (handlers.fake === undefined) return
      const bytes = handlers.fake.encodeReplies(message)
      if (bytes.byteLength > 0) feedFromAgent(bytes)
    },
  })
  feedFromAgent = transport.feedFromAgent
  const app = client({ name: "groks-beard" })
    .onRequest("terminal/create", (ctx) => {
      handlers.onTerminalCreate?.(ctx.params.command, state)
      return { terminalId: "beard-term" }
    })
    .onRequest(
      "session/request_permission",
      (ctx) =>
        handlers.onPermission?.(ctx.params, String(ctx.requestId)) ?? {
          outcome: { outcome: "cancelled" as const },
        },
    )
    .onRequest(
      "elicitation/create",
      (ctx) =>
        handlers.onElicit?.(ctx.params, String(ctx.requestId)) ?? { action: "cancel" as const },
    )
    .onRequest(
      "_x.ai/exit_plan_mode",
      (params: unknown) => params,
      (ctx) =>
        handlers.onExitPlanMode?.(ctx.params, String(ctx.requestId)) ?? {
          outcome: "cancelled" as const,
        },
    )
    .onRequest(
      "_x.ai/ask_user_question",
      (params: unknown) => params,
      (ctx) => handlers.onAskUserQuestion?.(ctx.params, String(ctx.requestId)) ?? { answers: [] },
    )
    .onNotification("session/update", (ctx) => {
      handlers.onSessionUpdate?.(ctx.params)
    })
  const connection = app.connect(transport.stream)
  return { connection, agent: connection.agent, transport, state }
}

export const initializeAgent = (
  agent: ClientContext,
  policy: CapabilityPolicy,
  extensionVersion: string,
): Effect.Effect<unknown, AcpError> =>
  Effect.tryPromise({
    try: () => agent.request("initialize", initializeParams(policy, extensionVersion)),
    catch: (cause) =>
      new AcpError({
        method: "initialize",
        message: cause instanceof Error ? cause.message : String(cause),
      }),
  })
