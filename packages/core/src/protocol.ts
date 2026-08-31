import { Schema } from "effect"
import { PromptChip } from "./prompt.js"
import { AgentQuestion, AskUserQuestionAnswer } from "./questions.js"

export const StopReasonView = Schema.Literals([
  "end_turn",
  "cancelled",
  "max_tokens",
  "max_turn_requests",
  "refusal",
  "unknown",
])

export type StopReasonView = typeof StopReasonView.Type

export const displayStopReason = (reason: string): StopReasonView => {
  const decoded = Schema.decodeUnknownExit(StopReasonView)(reason)
  return decoded._tag === "Success" ? decoded.value : "unknown"
}

const PermissionOption = Schema.Struct({
  optionId: Schema.String,
  name: Schema.String,
  kind: Schema.String,
})

const ToolRow = Schema.Struct({
  id: Schema.String,
  title: Schema.String,
  kind: Schema.String,
  status: Schema.String,
  additions: Schema.optionalKey(Schema.Number),
  deletions: Schema.optionalKey(Schema.Number),
  input: Schema.optionalKey(Schema.String),
  output: Schema.optionalKey(Schema.String),
})

const Occupancy = Schema.Struct({
  used: Schema.Number,
  size: Schema.Number,
})

export const ReasoningChoice = Schema.Struct({
  value: Schema.String,
  name: Schema.String,
})

export type ReasoningChoice = typeof ReasoningChoice.Type

const SessionReasoning = Schema.Struct({
  id: Schema.String,
  current: Schema.String,
  options: Schema.Array(ReasoningChoice),
})

const ModelReasoning = Schema.Struct({
  current: Schema.String,
  options: Schema.Array(ReasoningChoice),
})

const MentionFile = Schema.Struct({
  path: Schema.String,
  absPath: Schema.String,
})

const SlashCommand = Schema.Struct({
  name: Schema.String,
  description: Schema.String,
  hint: Schema.optionalKey(Schema.String),
})

export const SessionModeOption = Schema.Struct({
  id: Schema.String,
  name: Schema.String,
})

export type SessionModeOption = typeof SessionModeOption.Type

export const SessionModelOption = Schema.Struct({
  modelId: Schema.String,
  name: Schema.String,
  description: Schema.optionalKey(Schema.String),
  contextWindow: Schema.optionalKey(Schema.Number),
  reasoning: Schema.optionalKey(ModelReasoning),
})

export type SessionModelOption = typeof SessionModelOption.Type

export const HostMsg = Schema.Union([
  Schema.TaggedStruct("ready", {}),
  Schema.TaggedStruct("sessionMeta", {
    sessionId: Schema.String,
    title: Schema.String,
    modeId: Schema.String,
    modelId: Schema.optionalKey(Schema.String),
    occupancy: Schema.optionalKey(Occupancy),
    reasoning: Schema.optionalKey(SessionReasoning),
    availableModes: Schema.optionalKey(Schema.Array(SessionModeOption)),
    availableModels: Schema.optionalKey(Schema.Array(SessionModelOption)),
  }),
  Schema.TaggedStruct("userMessage", {
    turnId: Schema.String,
    text: Schema.String,
    chips: Schema.Array(PromptChip),
    steer: Schema.optionalKey(Schema.Boolean),
  }),
  Schema.TaggedStruct("agentChunk", {
    turnId: Schema.String,
    messageId: Schema.optionalKey(Schema.String),
    text: Schema.String,
  }),
  Schema.TaggedStruct("thoughtChunk", {
    turnId: Schema.String,
    text: Schema.String,
  }),
  Schema.TaggedStruct("toolGroup", {
    turnId: Schema.String,
    tools: Schema.Array(ToolRow),
  }),
  Schema.TaggedStruct("permissionCard", {
    requestId: Schema.String,
    toolCallId: Schema.String,
    title: Schema.String,
    options: Schema.Array(PermissionOption),
    hasDiff: Schema.Boolean,
  }),
  Schema.TaggedStruct("planCard", {
    requestId: Schema.String,
    planMarkdown: Schema.String,
  }),
  Schema.TaggedStruct("questionCard", {
    requestId: Schema.String,
    questions: Schema.Array(AgentQuestion),
  }),
  Schema.TaggedStruct("elicitCard", {
    requestId: Schema.String,
    serverName: Schema.String,
    mode: Schema.Literals(["form", "url"]),
    title: Schema.String,
    url: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("availableCommands", {
    commands: Schema.Array(SlashCommand),
  }),
  Schema.TaggedStruct("mentionResults", {
    query: Schema.String,
    files: Schema.Array(MentionFile),
  }),
  Schema.TaggedStruct("composerChip", {
    path: Schema.String,
    absPath: Schema.String,
    startLine: Schema.optionalKey(Schema.Number),
    endLine: Schema.optionalKey(Schema.Number),
    languageId: Schema.optionalKey(Schema.String),
    excerpt: Schema.optionalKey(Schema.String),
    source: Schema.Literals(["selection", "file", "active", "mention"]),
  }),
  Schema.TaggedStruct("turnEnd", {
    turnId: Schema.String,
    stopReason: StopReasonView,
  }),
  Schema.TaggedStruct("queued", {
    count: Schema.Number,
  }),
  Schema.TaggedStruct("changesSummary", {
    fileCount: Schema.Number,
    additions: Schema.Number,
    deletions: Schema.Number,
  }),
  Schema.TaggedStruct("editorContext", {
    path: Schema.optionalKey(Schema.String),
    startLine: Schema.optionalKey(Schema.Number),
    startCol: Schema.optionalKey(Schema.Number),
    endLine: Schema.optionalKey(Schema.Number),
    endCol: Schema.optionalKey(Schema.Number),
    hasSelection: Schema.Boolean,
    excerpt: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("settingsState", {
    cliPath: Schema.String,
    nodePath: Schema.String,
    includeActiveFileByDefault: Schema.Boolean,
    useCtrlEnterToSend: Schema.Boolean,
    changesPresentation: Schema.Literals(["toast", "pane"]),
  }),
  Schema.TaggedStruct("mcpCatalog", {
    loading: Schema.Boolean,
    healthyCount: Schema.Number,
    failingCount: Schema.Number,
    servers: Schema.Array(Schema.Struct({
      name: Schema.String,
      transport: Schema.String,
      source: Schema.String,
      healthy: Schema.Boolean,
      toolCount: Schema.optionalKey(Schema.Number),
      tools: Schema.optionalKey(Schema.Array(Schema.Struct({
        name: Schema.String,
        enabled: Schema.Boolean,
        description: Schema.optionalKey(Schema.String),
      }))),
      checks: Schema.Array(Schema.Struct({
        label: Schema.String,
        passed: Schema.Boolean,
        detail: Schema.optionalKey(Schema.String),
        hint: Schema.optionalKey(Schema.String),
      })),
    })),
    error: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("error", {
    code: Schema.optionalKey(Schema.String),
    message: Schema.String,
  }),
  Schema.TaggedStruct("clearTranscript", {}),
  Schema.TaggedStruct("restoreTranscript", {
    messages: Schema.Array(Schema.Unknown),
  }),
])

export type HostMsg = typeof HostMsg.Type

export const WebviewMsg = Schema.Union([
  Schema.TaggedStruct("ready", {}),
  Schema.TaggedStruct("send", {
    text: Schema.String,
    chips: Schema.Array(PromptChip),
  }),
  Schema.TaggedStruct("queue", {
    text: Schema.String,
    chips: Schema.Array(PromptChip),
  }),
  Schema.TaggedStruct("steer", {
    text: Schema.String,
    chips: Schema.Array(PromptChip),
  }),
  Schema.TaggedStruct("cancel", {}),
  Schema.TaggedStruct("permissionChoice", {
    requestId: Schema.String,
    optionId: Schema.String,
  }),
  Schema.TaggedStruct("permissionPark", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("openDiff", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("planVerdict", {
    requestId: Schema.String,
    verdict: Schema.Literals(["approved", "cancelled", "abandoned"]),
    comment: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("questionChoice", {
    requestId: Schema.String,
    answers: Schema.Array(AskUserQuestionAnswer),
  }),
  Schema.TaggedStruct("questionDismiss", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("questionPark", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("elicitAccept", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("elicitDecline", {
    requestId: Schema.String,
  }),
  Schema.TaggedStruct("slashPick", {
    name: Schema.String,
  }),
  Schema.TaggedStruct("mentionQuery", {
    query: Schema.String,
  }),
  Schema.TaggedStruct("mentionPick", {
    path: Schema.String,
    absPath: Schema.String,
  }),
  Schema.TaggedStruct("cycleMode", {}),
  Schema.TaggedStruct("setMode", {
    modeId: Schema.String,
  }),
  Schema.TaggedStruct("setModel", {
    modelId: Schema.String,
  }),
  Schema.TaggedStruct("sendNow", {}),
  Schema.TaggedStruct("setReasoning", {
    value: Schema.String,
    modelId: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("openSettings", {}),
  Schema.TaggedStruct("openChanges", {
    turnId: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("keepAllPending", {}),
  Schema.TaggedStruct("undoAllPending", {}),
  Schema.TaggedStruct("commitAllPending", {}),
  Schema.TaggedStruct("refreshMcp", {
    name: Schema.String,
  }),
  Schema.TaggedStruct("setMcpEnabled", {
    name: Schema.String,
    enabled: Schema.Boolean,
  }),
  Schema.TaggedStruct("setMcpToolEnabled", {
    name: Schema.String,
    tool: Schema.String,
    enabled: Schema.Boolean,
  }),
  Schema.TaggedStruct("revealEditor", {
    absPath: Schema.optionalKey(Schema.String),
    startLine: Schema.optionalKey(Schema.Number),
    endLine: Schema.optionalKey(Schema.Number),
  }),
  Schema.TaggedStruct("addSelection", {}),
  Schema.TaggedStruct("openPlan", {
    markdown: Schema.optionalKey(Schema.String),
  }),
  Schema.TaggedStruct("openMcpConfig", {}),
  Schema.TaggedStruct("openSettingsJson", {}),
  Schema.TaggedStruct("trustFolder", {}),
  Schema.TaggedStruct("setSetting", {
    key: Schema.Literals([
      "cliPath",
      "nodePath",
      "includeActiveFileByDefault",
      "useCtrlEnterToSend",
      "changesPresentation",
    ]),
    value: Schema.Union([Schema.String, Schema.Boolean]),
  }),
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
  composerChip: true,
  turnEnd: true,
  queued: true,
  changesSummary: true,
  editorContext: true,
  settingsState: true,
  mcpCatalog: true,
  error: true,
  clearTranscript: true,
  restoreTranscript: true,
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
  setMode: true,
  setModel: true,
  sendNow: true,
  setReasoning: true,
  openSettings: true,
  openChanges: true,
  keepAllPending: true,
  undoAllPending: true,
  commitAllPending: true,
  refreshMcp: true,
  setMcpEnabled: true,
  setMcpToolEnabled: true,
  revealEditor: true,
  addSelection: true,
  openPlan: true,
  openMcpConfig: true,
  openSettingsJson: true,
  trustFolder: true,
  setSetting: true,
}

export const HOST_MSG_TAGS = Object.keys(HOST_MSG_HANDLED) as Array<HostMsg["_tag"]>
export const WEBVIEW_MSG_TAGS = Object.keys(WEBVIEW_MSG_HANDLED) as Array<WebviewMsg["_tag"]>

export const decodeHostMsg = Schema.decodeUnknownSync(HostMsg)
export const decodeWebviewMsg = Schema.decodeUnknownSync(WebviewMsg)
