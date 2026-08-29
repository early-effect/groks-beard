import { Schema } from "effect"

export const KnownSessionUpdateTag = Schema.Literals([
  "agent_message_chunk",
  "agent_thought_chunk",
  "user_message_chunk",
  "tool_call",
  "tool_call_update",
  "plan",
  "available_commands_update",
  "current_mode_update",
  "usage_update",
  "session_info_update",
  "subagent_spawned",
])

export type KnownSessionUpdateTag = typeof KnownSessionUpdateTag.Type

export class UnknownUpdate extends Schema.Class<UnknownUpdate>("UnknownUpdate")({
  sessionUpdate: Schema.String,
  rest: Schema.Unknown,
}) {}

export class AgentMessageChunk extends Schema.Class<AgentMessageChunk>("AgentMessageChunk")({
  sessionUpdate: Schema.Literal("agent_message_chunk"),
  content: Schema.optionalKey(Schema.Unknown),
}) {}

export class AgentThoughtChunk extends Schema.Class<AgentThoughtChunk>("AgentThoughtChunk")({
  sessionUpdate: Schema.Literal("agent_thought_chunk"),
  content: Schema.optionalKey(Schema.Unknown),
}) {}

export class UserMessageChunk extends Schema.Class<UserMessageChunk>("UserMessageChunk")({
  sessionUpdate: Schema.Literal("user_message_chunk"),
  content: Schema.optionalKey(Schema.Unknown),
}) {}

export class ToolCallUpdate extends Schema.Class<ToolCallUpdate>("ToolCallUpdate")({
  sessionUpdate: Schema.Literals(["tool_call", "tool_call_update"]),
  toolCallId: Schema.optionalKey(Schema.String),
  title: Schema.optionalKey(Schema.String),
  kind: Schema.optionalKey(Schema.String),
  status: Schema.optionalKey(Schema.String),
  content: Schema.optionalKey(Schema.Unknown),
  locations: Schema.optionalKey(Schema.Unknown),
  rawInput: Schema.optionalKey(Schema.Unknown),
  rawOutput: Schema.optionalKey(Schema.Unknown),
}) {}

export class CurrentModeUpdate extends Schema.Class<CurrentModeUpdate>("CurrentModeUpdate")({
  sessionUpdate: Schema.Literal("current_mode_update"),
  modeId: Schema.optionalKey(Schema.String),
  currentModeId: Schema.optionalKey(Schema.String),
}) {}

export class AvailableCommandsUpdate
  extends Schema.Class<AvailableCommandsUpdate>("AvailableCommandsUpdate")({
    sessionUpdate: Schema.Literal("available_commands_update"),
    availableCommands: Schema.optionalKey(Schema.Unknown),
  })
{}

export class PlanUpdate extends Schema.Class<PlanUpdate>("PlanUpdate")({
  sessionUpdate: Schema.Literal("plan"),
  entries: Schema.optionalKey(Schema.Unknown),
}) {}

export class UsageUpdate extends Schema.Class<UsageUpdate>("UsageUpdate")({
  sessionUpdate: Schema.Literal("usage_update"),
  used: Schema.optionalKey(Schema.Number),
  size: Schema.optionalKey(Schema.Number),
}) {}

export const KnownSessionUpdate = Schema.Union([
  AgentMessageChunk,
  AgentThoughtChunk,
  UserMessageChunk,
  ToolCallUpdate,
  CurrentModeUpdate,
  AvailableCommandsUpdate,
  PlanUpdate,
  UsageUpdate,
])

export type SessionUpdate = typeof KnownSessionUpdate.Type | UnknownUpdate

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null

export const modeIdFromUpdate = (update: CurrentModeUpdate): string | undefined =>
  update.modeId ?? update.currentModeId

export const textFromContent = (content: unknown): string => {
  if (typeof content === "string") return content
  if (Array.isArray(content)) return content.map(textFromContent).join("")
  if (typeof content !== "object" || content === null) return ""
  const record = content as Record<string, unknown>
  if (typeof record.text === "string") return record.text
  if (record.content !== undefined) return textFromContent(record.content)
  return ""
}

export const sessionIdFromParams = (params: unknown): string | undefined => {
  if (!isRecord(params)) return undefined
  return typeof params.sessionId === "string" ? params.sessionId : undefined
}

export const updateFromParams = (params: unknown): unknown => {
  if (!isRecord(params)) return params
  return params.update !== undefined ? params.update : params
}

export const decodeSessionUpdate = (input: unknown): SessionUpdate => {
  const known = Schema.decodeUnknownExit(KnownSessionUpdate)(input)
  if (known._tag === "Success") return known.value
  if (isRecord(input) && typeof input.sessionUpdate === "string") {
    return new UnknownUpdate({ sessionUpdate: input.sessionUpdate, rest: input })
  }
  return new UnknownUpdate({ sessionUpdate: "unknown", rest: input })
}
