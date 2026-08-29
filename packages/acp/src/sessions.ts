import type { ClientContext } from "@agentclientprotocol/sdk"
import { Effect } from "effect"
import { AcpError, SessionLoadFailed, SessionLocked } from "@groks-beard/core"

export type NewSessionResult = {
  readonly sessionId: string
}

const errorMessage = (cause: unknown): string =>
  cause instanceof Error ? cause.message : String(cause)

const errorCode = (cause: unknown): number | undefined => {
  if (typeof cause === "object" && cause !== null && "code" in cause) {
    const code = (cause as { code: unknown }).code
    return typeof code === "number" ? code : undefined
  }
  return undefined
}

export const classifySessionLoadError = (
  cause: unknown,
  sessionId: string,
  cwd: string
): SessionLocked | SessionLoadFailed => {
  const message = errorMessage(cause)
  const lowered = message.toLowerCase()
  if (lowered.includes("lock") || lowered.includes("busy") || lowered.includes("in use")) {
    return new SessionLocked({ sessionId, cwd })
  }
  return new SessionLoadFailed({
    sessionId,
    reason: message
  })
}

export const loadErrorCopy = (
  error: SessionLocked | SessionLoadFailed
): { readonly title: string; readonly actions: ReadonlyArray<"fork" | "openTui" | "retry"> } => {
  if (error._tag === "SessionLocked") {
    return {
      title: "This session is open in the TUI",
      actions: ["fork", "openTui", "retry"]
    }
  }
  return { title: "Could not resume session", actions: ["retry"] }
}

export const newSession = (
  agent: ClientContext,
  cwd: string
): Effect.Effect<NewSessionResult, AcpError> =>
  Effect.tryPromise({
    try: async () => {
      const result = await agent.request("session/new", { cwd, mcpServers: [] }) as {
        sessionId?: string
      }
      if (result.sessionId === undefined) {
        throw new Error("session/new returned no sessionId")
      }
      return { sessionId: result.sessionId }
    },
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/new", message: errorMessage(cause) })
        : new AcpError({ method: "session/new", message: errorMessage(cause), code })
    }
  })

export const loadSession = (
  agent: ClientContext,
  sessionId: string,
  cwd: string
): Effect.Effect<NewSessionResult, SessionLocked | SessionLoadFailed | AcpError> =>
  Effect.tryPromise({
    try: async () => {
      await agent.request("session/load", { sessionId, cwd, mcpServers: [] })
      return { sessionId }
    },
    catch: (cause) => classifySessionLoadError(cause, sessionId, cwd)
  })

export const promptSession = (
  agent: ClientContext,
  sessionId: string,
  text: string
): Effect.Effect<{ readonly stopReason: string }, AcpError> =>
  Effect.tryPromise({
    try: async () => {
      const result = await agent.request("session/prompt", {
        sessionId,
        prompt: [{ type: "text", text }]
      }) as { stopReason?: string }
      return { stopReason: result.stopReason ?? "end_turn" }
    },
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/prompt", message: errorMessage(cause) })
        : new AcpError({ method: "session/prompt", message: errorMessage(cause), code })
    }
  })
