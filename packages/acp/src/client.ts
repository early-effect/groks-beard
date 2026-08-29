import {
  type AnyMessage,
  client,
  type ClientConnection,
  type ClientContext,
  RequestError,
} from "@agentclientprotocol/sdk"
import {
  AcpError,
  PLAN_BLOCKED_CODE,
  PLAN_BLOCKED_TERMINAL_MSG,
  shouldBlockTerminal,
} from "@groks-beard/core"
import { Effect } from "effect"
import { type CapabilityPolicy, initializeParams } from "./capabilities.js"
import { FakeGrokAgent } from "./fake-agent.js"
import { createFramedTransport, type FramedTransport } from "./framed-stream.js"
import { planTerminalAllowlistArmed } from "./plan-terminal-probe.js"
import { emptySessionState, type SessionState } from "./session-state.js"
import { MemoryTerminalManager, type TerminalManager } from "./terminal-manager.js"

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
  readonly terminal?: TerminalManager
}

export type BeardAcp = {
  readonly connection: ClientConnection
  readonly agent: ClientContext
  readonly transport: FramedTransport
  readonly state: SessionState
  readonly terminal: TerminalManager
}

export const connectBeardAcp = (handlers: BeardClientHandlers = {}): BeardAcp => {
  const state = emptySessionState()
  const terminal = handlers.terminal ?? new MemoryTerminalManager()
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
      if (
        planTerminalAllowlistArmed()
        && shouldBlockTerminal(
          ctx.params.command,
          ctx.params.args ?? [],
          state.planActive,
          terminal.shellDialect,
        )
      ) {
        throw new RequestError(PLAN_BLOCKED_CODE, PLAN_BLOCKED_TERMINAL_MSG)
      }
      return terminal.create({
        sessionId: ctx.params.sessionId,
        command: ctx.params.command,
        ...(ctx.params.args !== undefined ? { args: ctx.params.args } : {}),
        ...(ctx.params.env !== undefined ? { env: ctx.params.env } : {}),
        ...(ctx.params.cwd !== undefined ? { cwd: ctx.params.cwd } : {}),
        ...(ctx.params.outputByteLimit !== undefined
          ? { outputByteLimit: ctx.params.outputByteLimit }
          : {}),
      })
    })
    .onRequest("terminal/output", (ctx) => terminal.output(ctx.params.terminalId))
    .onRequest("terminal/wait_for_exit", (ctx) => terminal.waitForExit(ctx.params.terminalId))
    .onRequest("terminal/kill", (ctx) => {
      terminal.kill(ctx.params.terminalId)
      return {}
    })
    .onRequest("terminal/release", (ctx) => {
      terminal.release(ctx.params.terminalId)
      return {}
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
  return { connection, agent: connection.agent, transport, state, terminal }
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
