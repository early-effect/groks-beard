import type { ClientContext } from "@agentclientprotocol/sdk"
import {
  AcpError,
  occupancyFromUnknown,
  parseModelReasoning,
  parseReasoning,
  SessionLoadFailed,
  SessionLocked,
  type SessionModelOption,
  type SessionModeOption,
} from "@groks-beard/core"
import { Effect } from "effect"

export type NewSessionResult = {
  readonly sessionId: string
  readonly modeId?: string
  readonly availableModes?: ReadonlyArray<SessionModeOption>
  readonly modelId?: string
  readonly availableModels?: ReadonlyArray<SessionModelOption>
  readonly reasoning?: {
    readonly id: string
    readonly current: string
    readonly options: ReadonlyArray<{ readonly value: string; readonly name: string }>
  }
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null ? value as Record<string, unknown> : undefined

export const parseSessionModes = (
  modes: unknown,
): {
  readonly modeId?: string
  readonly availableModes?: ReadonlyArray<SessionModeOption>
} => {
  const rec = asRecord(modes)
  if (rec === undefined) return {}
  const modeId = typeof rec.currentModeId === "string" && rec.currentModeId !== ""
    ? rec.currentModeId
    : undefined
  const availableModes = Array.isArray(rec.availableModes)
    ? rec.availableModes.flatMap((item): Array<SessionModeOption> => {
      const mode = asRecord(item)
      if (mode === undefined || typeof mode.id !== "string" || mode.id === "") return []
      const name = typeof mode.name === "string" && mode.name !== "" ? mode.name : mode.id
      return [{ id: mode.id, name }]
    })
    : undefined
  return {
    ...(modeId !== undefined ? { modeId } : {}),
    ...(availableModes !== undefined && availableModes.length > 0 ? { availableModes } : {}),
  }
}

export const parseSessionModels = (
  models: unknown,
): {
  readonly modelId?: string
  readonly availableModels?: ReadonlyArray<SessionModelOption>
} => {
  const rec = asRecord(models)
  if (rec === undefined) return {}
  const modelId = typeof rec.currentModelId === "string" && rec.currentModelId !== ""
    ? rec.currentModelId
    : undefined
  const availableModels = Array.isArray(rec.availableModels)
    ? rec.availableModels.flatMap((item): Array<SessionModelOption> => {
      const model = asRecord(item)
      if (model === undefined || typeof model.modelId !== "string" || model.modelId === "") {
        return []
      }
      const name = typeof model.name === "string" && model.name !== ""
        ? model.name
        : model.modelId
      const description = typeof model.description === "string" && model.description !== ""
        ? model.description
        : undefined
      const meta = asRecord(model._meta)
      const contextWindow = typeof model.contextWindow === "number"
        ? model.contextWindow
        : typeof model.context_window === "number"
        ? model.context_window
        : typeof meta?.context_window === "number"
        ? meta.context_window
        : typeof meta?.contextWindow === "number"
        ? meta.contextWindow
        : undefined
      const reasoning = parseModelReasoning(model)
      return [{
        modelId: model.modelId,
        name,
        ...(description !== undefined ? { description } : {}),
        ...(contextWindow !== undefined && contextWindow > 0 ? { contextWindow } : {}),
        ...(reasoning !== undefined ? { reasoning } : {}),
      }]
    })
    : undefined
  return {
    ...(modelId !== undefined ? { modelId } : {}),
    ...(availableModels !== undefined && availableModels.length > 0 ? { availableModels } : {}),
  }
}

export const parseNewSessionResult = (result: unknown): NewSessionResult => {
  const rec = asRecord(result)
  if (rec === undefined || typeof rec.sessionId !== "string" || rec.sessionId === "") {
    throw new Error("session/new returned no sessionId")
  }
  const reasoning = parseReasoning(rec.configOptions)
  return {
    sessionId: rec.sessionId,
    ...parseSessionModes(rec.modes),
    ...parseSessionModels(rec.models),
    ...(reasoning !== undefined ? { reasoning } : {}),
  }
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
  cwd: string,
): SessionLocked | SessionLoadFailed => {
  const message = errorMessage(cause)
  const lowered = message.toLowerCase()
  if (lowered.includes("lock") || lowered.includes("busy") || lowered.includes("in use")) {
    return new SessionLocked({ sessionId, cwd })
  }
  return new SessionLoadFailed({
    sessionId,
    reason: message,
  })
}

export const loadErrorCopy = (
  error: SessionLocked | SessionLoadFailed,
): { readonly title: string; readonly actions: ReadonlyArray<"fork" | "openTui" | "retry"> } => {
  if (error._tag === "SessionLocked") {
    return {
      title: "This session is open in the TUI",
      actions: ["fork", "openTui", "retry"],
    }
  }
  return { title: "Could not resume session", actions: ["retry"] }
}

export const newSession = (
  agent: ClientContext,
  cwd: string,
  mcpServers: ReadonlyArray<unknown> = [],
): Effect.Effect<NewSessionResult, AcpError> =>
  Effect.tryPromise({
    try: async () =>
      parseNewSessionResult(
        await agent.request("session/new", { cwd, mcpServers: [...mcpServers] }),
      ),
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/new", message: errorMessage(cause) })
        : new AcpError({ method: "session/new", message: errorMessage(cause), code })
    },
  })

export const loadSession = (
  agent: ClientContext,
  sessionId: string,
  cwd: string,
): Effect.Effect<NewSessionResult, SessionLocked | SessionLoadFailed | AcpError> =>
  Effect.tryPromise({
    try: async () => {
      await agent.request("session/load", { sessionId, cwd, mcpServers: [] })
      return { sessionId }
    },
    catch: (cause) => classifySessionLoadError(cause, sessionId, cwd),
  })

export const promptSession = (
  agent: ClientContext,
  sessionId: string,
  text: string,
): Effect.Effect<{
  readonly stopReason: string
  readonly occupancy?: { used: number; size: number }
}, AcpError> =>
  Effect.tryPromise({
    try: async () => {
      const result = await agent.request("session/prompt", {
        sessionId,
        prompt: [{ type: "text", text }],
      })
      const rec = asRecord(result)
      const occupancy = occupancyFromUnknown(result)
      return {
        stopReason: typeof rec?.stopReason === "string" ? rec.stopReason : "end_turn",
        ...(occupancy !== undefined ? { occupancy } : {}),
      }
    },
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/prompt", message: errorMessage(cause) })
        : new AcpError({ method: "session/prompt", message: errorMessage(cause), code })
    },
  })

export const mcpReloadAttempts = (
  sessionId: string,
  name?: string,
): ReadonlyArray<{ readonly method: string; readonly params: Record<string, unknown> }> => {
  const named = name !== undefined && name !== ""
    ? [{ method: "_x.ai/mcp/setup", params: { sessionId, name } }]
    : []
  return [
    ...named,
    { method: "_x.ai/internal/reload_project_mcp_servers", params: { sessionId } },
    { method: "_x.ai/internal/reload_all_mcp_servers", params: { sessionId } },
  ]
}

export const listSessionMcp = async (
  agent: ClientContext,
  sessionId: string,
): Promise<unknown> => agent.request("_x.ai/mcp/list", { sessionId })

export const toggleSessionMcpTool = async (
  agent: ClientContext,
  sessionId: string,
  serverName: string,
  toolName: string,
  enabled: boolean,
): Promise<void> => {
  await agent.request("_x.ai/mcp/toggle_tool", {
    session_id: sessionId,
    server_name: serverName,
    tool_name: toolName,
    enabled,
  })
}

export const reloadSessionMcp = async (
  agent: ClientContext,
  sessionId: string,
  name?: string,
): Promise<boolean> => {
  for (const attempt of mcpReloadAttempts(sessionId, name)) {
    try {
      await agent.request(attempt.method, attempt.params)
      return true
    } catch {
      continue
    }
  }
  return false
}

export const cancelSession = (
  agent: ClientContext,
  sessionId: string,
): Effect.Effect<void, AcpError> =>
  Effect.tryPromise({
    try: () => agent.notify("session/cancel", { sessionId }),
    catch: (cause) => new AcpError({ method: "session/cancel", message: errorMessage(cause) }),
  })

export const setSessionMode = (
  agent: ClientContext,
  sessionId: string,
  modeId: string,
): Effect.Effect<void, AcpError> =>
  Effect.tryPromise({
    try: async () => {
      await agent.request("session/set_mode", { sessionId, modeId })
    },
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/set_mode", message: errorMessage(cause) })
        : new AcpError({ method: "session/set_mode", message: errorMessage(cause), code })
    },
  })

export const setSessionModel = (
  agent: ClientContext,
  sessionId: string,
  modelId: string,
): Effect.Effect<void, AcpError> =>
  Effect.tryPromise({
    try: async () => {
      await agent.request("session/set_model", { sessionId, modelId })
    },
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/set_model", message: errorMessage(cause) })
        : new AcpError({ method: "session/set_model", message: errorMessage(cause), code })
    },
  })

export const setSessionConfigOption = (
  agent: ClientContext,
  sessionId: string,
  configId: string,
  value: string,
): Effect.Effect<unknown, AcpError> =>
  Effect.tryPromise({
    try: async () => agent.request("session/set_config_option", { sessionId, configId, value }),
    catch: (cause) => {
      const code = errorCode(cause)
      return code === undefined
        ? new AcpError({ method: "session/set_config_option", message: errorMessage(cause) })
        : new AcpError({
          method: "session/set_config_option",
          message: errorMessage(cause),
          code,
        })
    },
  })
