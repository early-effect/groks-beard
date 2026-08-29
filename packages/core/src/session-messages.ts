import { diffsFromRawInput } from "./diff-content.js"
import type { HostMsg } from "./protocol.js"
import {
  CurrentModeUpdate,
  decodeSessionUpdate,
  sessionIdFromParams,
  type SessionUpdate,
  textFromContent,
  ToolCallUpdate,
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
} => ({
  id: update.toolCallId ?? "tool",
  title: update.title ?? "Tool",
  kind: update.kind ?? "other",
  status: update.status ?? "pending",
})

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

const sessionMeta = (
  params: unknown,
  modeId: string,
  occupancy?: { used: number; size: number },
): Extract<HostMsg, { _tag: "sessionMeta" }> => {
  const sessionId = sessionIdFromParams(params) ?? ""
  return occupancy !== undefined
    ? { _tag: "sessionMeta", sessionId, title: "", modeId, occupancy }
    : { _tag: "sessionMeta", sessionId, title: "", modeId }
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
  if (decoded instanceof UsageUpdate) {
    if (decoded.used === undefined || decoded.size === undefined) return []
    return [sessionMeta(params, "", { used: decoded.used, size: decoded.size })]
  }
  return []
}
