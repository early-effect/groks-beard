import { Schema } from "effect"
import { PromptChip } from "./prompt.js"
import { AgentQuestion, AskUserQuestionAnswer } from "./questions.js"

export const StopReasonView = Schema.Literals([
  "end_turn",
  "cancelled",
  "max_tokens",
  "max_turn_requests",
  "refusal",
  "unknown"
])

export type StopReasonView = typeof StopReasonView.Type

export const displayStopReason = (reason: string): StopReasonView => {
  const decoded = Schema.decodeUnknownExit(StopReasonView)(reason)
  return decoded._tag === "Success" ? decoded.value : "unknown"
}

const PermissionOption = Schema.Struct({
  optionId: Schema.String,
  name: Schema.String,
  kind: Schema.String
})

const ToolRow = Schema.Struct({
  id: Schema.String,
  title: Schema.String,
  kind: Schema.String,
  status: Schema.String,
  additions: Schema.optionalKey(Schema.Number),
  deletions: Schema.optionalKey(Schema.Number)
})

const Occupancy = Schema.Struct({
  used: Schema.Number,
  size: Schema.Number
})

const MentionFile = Schema.Struct({
  path: Schema.String,
  absPath: Schema.String
})

const SlashCommand = Schema.Struct({
  name: Schema.String,
  description: Schema.String,
  hint: Schema.optionalKey(Schema.String)
})

export const HostMsg = Schema.Union([
  Schema.TaggedStruct("ready", {}),
  Schema.TaggedStruct("sessionMeta", {
    sessionId: Schema.String,
    title: Schema.String,
    modeId: Schema.String,
    modelId: Schema.optionalKey(Schema.String),
    occupancy: Schema.optionalKey(Occupancy)
  }),
  Schema.TaggedStruct("userMessage", {
    turnId: Schema.String,
    text: Schema.String,
    chips: Schema.Array(PromptChip),
    steer: Schema.optionalKey(Schema.Boolean)
  }),
  Schema.TaggedStruct("agentChunk", {
    turnId: Schema.String,
    messageId: Schema.optionalKey(Schema.String),
    text: Schema.String
  }),
  Schema.TaggedStruct("thoughtChunk", {
    turnId: Schema.String,
    text: Schema.String
  }),
  Schema.TaggedStruct("toolGroup", {
    turnId: Schema.String,
    tools: Schema.Array(ToolRow)
  }),
  Schema.TaggedStruct("permissionCard", {
    requestId: Schema.String,
    toolCallId: Schema.String,
    title: Schema.String,
    options: Schema.Array(PermissionOption),
    hasDiff: Schema.Boolean
  }),
  Schema.TaggedStruct("planCard", {
    requestId: Schema.String,
    planMarkdown: Schema.String
  }),
  Schema.TaggedStruct("questionCard", {
    requestId: Schema.String,
    questions: Schema.Array(AgentQuestion)
  }),
  Schema.TaggedStruct("elicitCard", {
    requestId: Schema.String,
    serverName: Schema.String,
    mode: Schema.Literals(["form", "url"]),
    title: Schema.String,
    url: Schema.optionalKey(Schema.String)
  }),
  Schema.TaggedStruct("availableCommands", {
    commands: Schema.Array(SlashCommand)
  }),
  Schema.TaggedStruct("mentionResults", {
    query: Schema.String,
    files: Schema.Array(MentionFile)
  }),
  Schema.TaggedStruct("turnEnd", {
    turnId: Schema.String,
    stopReason: StopReasonView
  }),
  Schema.TaggedStruct("queued", {
    count: Schema.Number
  }),
  Schema.TaggedStruct("error", {
    code: Schema.optionalKey(Schema.String),
    message: Schema.String
  }),
  Schema.TaggedStruct("clearTranscript", {}),
  Schema.TaggedStruct("restoreTranscript", {
    messages: Schema.Array(Schema.Unknown)
  })
])

export type HostMsg = typeof HostMsg.Type

export const WebviewMsg = Schema.Union([
  Schema.TaggedStruct("ready", {}),
  Schema.TaggedStruct("send", {
    text: Schema.String,
    chips: Schema.Array(PromptChip)
  }),
  Schema.TaggedStruct("queue", {
    text: Schema.String,
    chips: Schema.Array(PromptChip)
  }),
  Schema.TaggedStruct("steer", {
    text: Schema.String,
    chips: Schema.Array(PromptChip)
  }),
  Schema.TaggedStruct("cancel", {}),
  Schema.TaggedStruct("permissionChoice", {
    requestId: Schema.String,
    optionId: Schema.String
  }),
  Schema.TaggedStruct("permissionPark", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("openDiff", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("planVerdict", {
    requestId: Schema.String,
    verdict: Schema.Literals(["approved", "cancelled", "abandoned"]),
    comment: Schema.optionalKey(Schema.String)
  }),
  Schema.TaggedStruct("questionChoice", {
    requestId: Schema.String,
    answers: Schema.Array(AskUserQuestionAnswer)
  }),
  Schema.TaggedStruct("questionDismiss", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("questionPark", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("elicitAccept", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("elicitDecline", {
    requestId: Schema.String
  }),
  Schema.TaggedStruct("slashPick", {
    name: Schema.String
  }),
  Schema.TaggedStruct("mentionQuery", {
    query: Schema.String
  }),
  Schema.TaggedStruct("mentionPick", {
    path: Schema.String,
    absPath: Schema.String
  }),
  Schema.TaggedStruct("cycleMode", {}),
  Schema.TaggedStruct("openChanges", {
    turnId: Schema.optionalKey(Schema.String)
  })
])

export type WebviewMsg = typeof WebviewMsg.Type

export const HOST_MSG_HANDLED: Record<HostMsg["_tag"], true> = {
  ready: true,
  sessionMeta: true,
  userMessage: true,
  agentChunk: true,
  thoughtChunk: true,
  toolGroup: true,
  permissionCard: true,
  planCard: true,
  questionCard: true,
  elicitCard: true,
  availableCommands: true,
  mentionResults: true,
  turnEnd: true,
  queued: true,
  error: true,
  clearTranscript: true,
  restoreTranscript: true
}

export const WEBVIEW_MSG_HANDLED: Record<WebviewMsg["_tag"], true> = {
  ready: true,
  send: true,
  queue: true,
  steer: true,
  cancel: true,
  permissionChoice: true,
  permissionPark: true,
  openDiff: true,
  planVerdict: true,
  questionChoice: true,
  questionDismiss: true,
  questionPark: true,
  elicitAccept: true,
  elicitDecline: true,
  slashPick: true,
  mentionQuery: true,
  mentionPick: true,
  cycleMode: true,
  openChanges: true
}

export const HOST_MSG_TAGS = Object.keys(HOST_MSG_HANDLED) as Array<HostMsg["_tag"]>
export const WEBVIEW_MSG_TAGS = Object.keys(WEBVIEW_MSG_HANDLED) as Array<WebviewMsg["_tag"]>

export const decodeHostMsg = Schema.decodeUnknownSync(HostMsg)
export const decodeWebviewMsg = Schema.decodeUnknownSync(WebviewMsg)
