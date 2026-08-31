import { diffsFromRawInput } from "./diff-content.js"
import type { HostMsg, SessionModelOption } from "./protocol.js"
import {
  ConfigOptionUpdate,
  CurrentModeUpdate,
  decodeSessionUpdate,
  occupancyFromUnknown,
  sessionIdFromParams,
  type SessionUpdate,
  textFromContent,
  ToolCallUpdate,
  toolPayloadText,
  updateFromParams,
  UsageUpdate,
} from "./session-update.js"

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null

export const slashCommandsFromUnknown = (
  value: unknown,
): Array<{ name: string; description: string; hint?: string }> => {
  if (!Array.isArray(value)) return []
  const out: Array<{ name: string; description: string; hint?: string }> = []
  for (const item of value) {
    if (!isRecord(item) || typeof item.name !== "string") continue
    const description = typeof item.description === "string" ? item.description : ""
    const input = isRecord(item.input) ? item.input : undefined
    const hint = typeof input?.hint === "string"
      ? input.hint
      : typeof item.hint === "string"
      ? item.hint
      : undefined
    out.push(
      hint !== undefined
        ? { name: item.name, description, hint }
        : { name: item.name, description },
    )
  }
  return out
}

const toolRowFromUpdate = (
  update: ToolCallUpdate,
): {
  id: string
  title: string
  kind: string
  status: string
  input?: string
  output?: string
} => {
  const input = update.rawInput !== undefined ? toolPayloadText(update.rawInput) : ""
  const output = update.content !== undefined
    ? toolPayloadText(update.content)
    : update.rawOutput !== undefined
    ? toolPayloadText(update.rawOutput)
    : ""
  return {
    id: update.toolCallId ?? "tool",
    title: update.title ?? "",
    kind: update.kind ?? "",
    status: update.status ?? "pending",
    ...(input !== "" ? { input } : {}),
    ...(output !== "" ? { output } : {}),
  }
}

const permissionOptions = (
  value: unknown,
): Array<{ optionId: string; name: string; kind: string }> => {
  if (!Array.isArray(value)) return []
  const out: Array<{ optionId: string; name: string; kind: string }> = []
  for (const item of value) {
    if (!isRecord(item) || typeof item.optionId !== "string") continue
    out.push({
      optionId: item.optionId,
      name: typeof item.name === "string" ? item.name : item.optionId,
      kind: typeof item.kind === "string" ? item.kind : "other",
    })
  }
  return out
}

const contentHasDiff = (content: unknown): boolean => {
  if (typeof content === "string") return content.includes('"type":"diff"')
  try {
    return JSON.stringify(content).includes('"type":"diff"')
  } catch {
    return false
  }
}

export const permissionCardFromParams = (
  params: unknown,
  requestId: string,
): Extract<HostMsg, { _tag: "permissionCard" }> => {
  const rec = isRecord(params) ? params : {}
  const toolCall = isRecord(rec.toolCall) ? rec.toolCall : {}
  const toolCallId = typeof toolCall.toolCallId === "string" ? toolCall.toolCallId : requestId
  const title = typeof toolCall.title === "string" ? toolCall.title : "Permission"
  return {
    _tag: "permissionCard",
    requestId,
    toolCallId,
    title,
    options: permissionOptions(rec.options),
    hasDiff: contentHasDiff(toolCall.content) || diffsFromRawInput(toolCall.rawInput).length > 0,
  }
}

export const elicitCardFromParams = (
  params: unknown,
  requestId: string,
): Extract<HostMsg, { _tag: "elicitCard" }> => {
  const rec = isRecord(params) ? params : {}
  const mode = rec.mode === "url" ? "url" as const : "form" as const
  const title = typeof rec.message === "string"
    ? rec.message
    : typeof rec.title === "string"
    ? rec.title
    : "Input required"
  const url = typeof rec.url === "string" ? rec.url : undefined
  const serverName = typeof rec.serverName === "string" ? rec.serverName : "mcp"
  return url !== undefined
    ? { _tag: "elicitCard", requestId, serverName, mode, title, url }
    : { _tag: "elicitCard", requestId, serverName, mode, title }
}

const flattenSelectOptions = (
  options: unknown,
): Array<{ value: string; name: string }> => {
  if (!Array.isArray(options)) return []
  return options.flatMap((item) => {
    if (!isRecord(item)) return []
    if (Array.isArray(item.options)) return flattenSelectOptions(item.options)
    if (typeof item.value !== "string" || item.value === "") return []
    const name = typeof item.name === "string" && item.name !== "" ? item.name : item.value
    return [{ value: item.value, name }]
  })
}

export const parseReasoning = (
  configOptions: unknown,
): Extract<HostMsg, { _tag: "sessionMeta" }>["reasoning"] => {
  if (!Array.isArray(configOptions)) return undefined
  for (const item of configOptions) {
    if (!isRecord(item) || typeof item.id !== "string" || item.id === "") continue
    const category = typeof item.category === "string" ? item.category : ""
    const name = typeof item.name === "string" ? item.name : item.id
    if (category !== "thought_level" && !/effort|reason/i.test(`${item.id} ${name}`)) {
      continue
    }
    const options = flattenSelectOptions(item.options)
    const current = typeof item.currentValue === "string" ? item.currentValue : options[0]?.value
    if (current === undefined || options.length === 0) continue
    return { id: item.id, current, options }
  }
  return undefined
}

export const FALLBACK_REASONING_OPTIONS: ReadonlyArray<{ value: string; name: string }> = [
  { value: "low", name: "Low" },
  { value: "medium", name: "Medium" },
  { value: "high", name: "High" },
  { value: "xhigh", name: "Extra high" },
]

export type ModelReasoningView = {
  readonly current: string
  readonly options: ReadonlyArray<{ readonly value: string; readonly name: string }>
}

const numberish = (value: unknown): number | undefined =>
  typeof value === "number" && Number.isFinite(value) ? value : undefined

export const parseReasoningEfforts = (
  value: unknown,
): Array<{ value: string; name: string }> => {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    if (!isRecord(item)) return []
    if (Array.isArray(item.options)) return parseReasoningEfforts(item.options)
    const optionValue = typeof item.value === "string" && item.value !== ""
      ? item.value
      : typeof item.id === "string" && item.id !== ""
      ? item.id
      : ""
    if (optionValue === "") return []
    const name = typeof item.name === "string" && item.name !== ""
      ? item.name
      : typeof item.label === "string" && item.label !== ""
      ? item.label
      : optionValue
    return [{ value: optionValue, name }]
  })
}

const defaultEffortValue = (
  efforts: unknown,
  options: ReadonlyArray<{ value: string; name: string }>,
): string | undefined => {
  if (Array.isArray(efforts)) {
    for (const item of efforts) {
      if (!isRecord(item) || item.default !== true) continue
      if (typeof item.value === "string" && item.value !== "") return item.value
      if (typeof item.id === "string" && item.id !== "") return item.id
    }
  }
  return options[0]?.value
}

const modelReasoningFrom = (source: Record<string, unknown>): ModelReasoningView | undefined => {
  const options = parseReasoningEfforts(source.reasoning_efforts)
  if (options.length === 0) return undefined
  const allowed = new Set(options.map((item) => item.value))
  const advertised = typeof source.reasoning_effort === "string"
    ? source.reasoning_effort
    : undefined
  const current = advertised !== undefined && allowed.has(advertised)
    ? advertised
    : defaultEffortValue(source.reasoning_efforts, options) ?? options[0]!.value
  return { current, options }
}

export const parseModelReasoning = (model: unknown): ModelReasoningView | undefined => {
  if (!isRecord(model)) return undefined
  const meta = isRecord(model._meta) ? model._meta : undefined
  const info = isRecord(model.info) ? model.info : undefined
  return modelReasoningFrom(model)
    ?? (meta !== undefined ? modelReasoningFrom(meta) : undefined)
    ?? (info !== undefined ? modelReasoningFrom(info) : undefined)
}

const catalogEntry = (
  catalog: unknown,
  modelId: string,
): Record<string, unknown> | undefined => {
  if (!isRecord(catalog)) return undefined
  const models = isRecord(catalog.models) ? catalog.models : catalog
  const entry = models[modelId]
  if (!isRecord(entry)) return undefined
  return isRecord(entry.info) ? entry.info : entry
}

export const overlayModelCatalog = (
  models: ReadonlyArray<SessionModelOption>,
  catalog: unknown,
): Array<SessionModelOption> => {
  if (catalog === undefined) return [...models]
  return models.map((model) => {
    const info = catalogEntry(catalog, model.modelId)
    if (info === undefined) return model
    const reasoning = model.reasoning ?? parseModelReasoning(info)
    const contextWindow = model.contextWindow
      ?? numberish(info.context_window)
      ?? numberish(info.contextWindow)
    if (reasoning === undefined && (contextWindow === undefined || contextWindow <= 0)) {
      return model
    }
    return {
      ...model,
      ...(contextWindow !== undefined && contextWindow > 0 ? { contextWindow } : {}),
      ...(reasoning !== undefined ? { reasoning } : {}),
    }
  })
}

const sessionMeta = (
  params: unknown,
  modeId: string,
  occupancy?: { used: number; size: number },
  reasoning?: Extract<HostMsg, { _tag: "sessionMeta" }>["reasoning"],
): Extract<HostMsg, { _tag: "sessionMeta" }> => {
  const sessionId = sessionIdFromParams(params) ?? ""
  return {
    _tag: "sessionMeta",
    sessionId,
    title: "",
    modeId,
    ...(occupancy !== undefined ? { occupancy } : {}),
    ...(reasoning !== undefined ? { reasoning } : {}),
  }
}

export const hostMsgsFromSessionUpdate = (
  params: unknown,
  turnId: string,
): ReadonlyArray<HostMsg> => {
  const decoded = decodeSessionUpdate(updateFromParams(params))
  return hostMsgsFromDecoded(decoded, params, turnId)
}

const hostMsgsFromDecoded = (
  decoded: SessionUpdate,
  params: unknown,
  turnId: string,
): ReadonlyArray<HostMsg> => {
  if (decoded.sessionUpdate === "agent_thought_chunk" && "content" in decoded) {
    const text = textFromContent(decoded.content)
    if (text === "") return []
    return [{ _tag: "thoughtChunk", turnId, text }]
  }
  if (decoded.sessionUpdate === "agent_message_chunk" && "content" in decoded) {
    const text = textFromContent(decoded.content)
    if (text === "") return []
    return [{ _tag: "agentChunk", turnId, text }]
  }
  if (decoded instanceof ToolCallUpdate) {
    return [{
      _tag: "toolGroup",
      turnId,
      tools: [toolRowFromUpdate(decoded)],
    }]
  }
  if (decoded.sessionUpdate === "available_commands_update" && "availableCommands" in decoded) {
    return [{
      _tag: "availableCommands",
      commands: slashCommandsFromUnknown(decoded.availableCommands),
    }]
  }
  if (decoded instanceof CurrentModeUpdate) {
    const modeId = decoded.modeId ?? decoded.currentModeId
    if (modeId === undefined) return []
    return [sessionMeta(params, modeId)]
  }
  if (decoded instanceof UsageUpdate || decoded.sessionUpdate === "usage_update") {
    const occupancy = occupancyFromUnknown(updateFromParams(params))
      ?? occupancyFromUnknown(decoded)
    if (occupancy === undefined) return []
    return [sessionMeta(params, "", occupancy)]
  }
  if (decoded instanceof ConfigOptionUpdate || decoded.sessionUpdate === "config_option_update") {
    const options = decoded instanceof ConfigOptionUpdate
      ? decoded.configOptions
      : isRecord(updateFromParams(params))
      ? (updateFromParams(params) as Record<string, unknown>).configOptions
      : undefined
    const reasoning = parseReasoning(options)
    if (reasoning === undefined) return []
    return [sessionMeta(params, "", undefined, reasoning)]
  }
  return []
}
