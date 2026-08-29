import {
  decodeHostMsg,
  HOST_MSG_HANDLED,
  type HostMsg,
  type PromptChip,
  type StopReasonView,
} from "@groks-beard/core"

export type ToolRow = {
  readonly id: string
  readonly title: string
  readonly kind: string
  readonly status: string
  readonly additions?: number
  readonly deletions?: number
}

export type TurnView = {
  readonly id: string
  readonly user?: {
    readonly text: string
    readonly chips: ReadonlyArray<PromptChip>
    readonly steer?: boolean
  }
  readonly thought: string
  readonly agent: string
  readonly tools: ReadonlyArray<ToolRow>
  readonly stopReason?: StopReasonView
}

export type ChatModel = {
  readonly session?: {
    readonly sessionId: string
    readonly title: string
    readonly modeId: string
    readonly modelId?: string
    readonly occupancy?: { readonly used: number; readonly size: number }
  }
  readonly turns: ReadonlyArray<TurnView>
  readonly commands: ReadonlyArray<{ name: string; description: string; hint?: string }>
  readonly mentionQuery: string
  readonly mentionFiles: ReadonlyArray<{ path: string; absPath: string }>
  readonly permission?: Extract<HostMsg, { _tag: "permissionCard" }>
  readonly plan?: Extract<HostMsg, { _tag: "planCard" }>
  readonly question?: Extract<HostMsg, { _tag: "questionCard" }>
  readonly elicit?: Extract<HostMsg, { _tag: "elicitCard" }>
  readonly queued: number
  readonly error?: { readonly code?: string; readonly message: string }
}

export const emptyChatModel = (): ChatModel => ({
  turns: [],
  commands: [],
  mentionQuery: "",
  mentionFiles: [],
  queued: 0,
})

const emptyTurn = (id: string): TurnView => ({
  id,
  thought: "",
  agent: "",
  tools: [],
})

const upsertTurn = (
  model: ChatModel,
  turnId: string,
  patch: (turn: TurnView) => TurnView,
): ChatModel => {
  const idx = model.turns.findIndex((turn) => turn.id === turnId)
  if (idx === -1) {
    return { ...model, turns: [...model.turns, patch(emptyTurn(turnId))] }
  }
  const next = model.turns.slice()
  const current = next[idx]
  if (current === undefined) return model
  next[idx] = patch(current)
  return { ...model, turns: next }
}

const mergeTools = (
  existing: ReadonlyArray<ToolRow>,
  incoming: ReadonlyArray<ToolRow>,
): ReadonlyArray<ToolRow> => {
  const next = existing.slice()
  for (const row of incoming) {
    const idx = next.findIndex((tool) => tool.id === row.id)
    if (idx === -1) next.push(row)
    else next[idx] = { ...next[idx]!, ...row }
  }
  return next
}

type SessionView = NonNullable<ChatModel["session"]>

const mergeSession = (
  model: ChatModel,
  msg: Extract<HostMsg, { _tag: "sessionMeta" }>,
): SessionView => {
  const previous = model.session
  const modelId = msg.modelId ?? previous?.modelId
  const occupancy = msg.occupancy ?? previous?.occupancy
  return {
    sessionId: msg.sessionId !== "" ? msg.sessionId : previous?.sessionId ?? "",
    title: msg.title !== "" ? msg.title : previous?.title ?? "",
    modeId: msg.modeId !== "" ? msg.modeId : previous?.modeId ?? "",
    ...(modelId !== undefined ? { modelId } : {}),
    ...(occupancy !== undefined ? { occupancy } : {}),
  }
}

export const applyHostMsg = (model: ChatModel, msg: HostMsg): ChatModel => {
  switch (msg._tag) {
    case "ready":
      return model
    case "sessionMeta":
      return { ...model, session: mergeSession(model, msg) }
    case "userMessage":
      return upsertTurn(model, msg.turnId, (turn) => ({
        ...turn,
        user: {
          text: msg.text,
          chips: msg.chips,
          ...(msg.steer !== undefined ? { steer: msg.steer } : {}),
        },
      }))
    case "agentChunk":
      return upsertTurn(model, msg.turnId, (turn) => ({
        ...turn,
        agent: `${turn.agent}${msg.text}`,
      }))
    case "thoughtChunk":
      return upsertTurn(model, msg.turnId, (turn) => ({
        ...turn,
        thought: `${turn.thought}${msg.text}`,
      }))
    case "toolGroup":
      return upsertTurn(model, msg.turnId, (turn) => ({
        ...turn,
        tools: mergeTools(turn.tools, msg.tools),
      }))
    case "permissionCard":
      return { ...model, permission: msg }
    case "planCard":
      return { ...model, plan: msg }
    case "questionCard":
      return { ...model, question: msg }
    case "elicitCard":
      return { ...model, elicit: msg }
    case "availableCommands":
      return { ...model, commands: [...msg.commands] }
    case "mentionResults":
      return { ...model, mentionQuery: msg.query, mentionFiles: [...msg.files] }
    case "turnEnd": {
      const {
        permission: _permission,
        plan: _plan,
        question: _question,
        elicit: _elicit,
        ...rest
      } = model
      return upsertTurn({ ...rest, queued: 0 }, msg.turnId, (turn) => ({
        ...turn,
        stopReason: msg.stopReason,
      }))
    }
    case "queued":
      return { ...model, queued: msg.count }
    case "error":
      return {
        ...model,
        error: msg.code !== undefined
          ? { code: msg.code, message: msg.message }
          : { message: msg.message },
      }
    case "clearTranscript":
      return {
        ...emptyChatModel(),
        ...(model.session !== undefined ? { session: model.session } : {}),
        commands: model.commands,
      }
    case "restoreTranscript": {
      let next: ChatModel = {
        ...emptyChatModel(),
        ...(model.session !== undefined ? { session: model.session } : {}),
        commands: model.commands,
      }
      for (const raw of msg.messages) {
        try {
          next = applyHostMsg(next, decodeHostMsg(raw))
        } catch {
          continue
        }
      }
      return next
    }
  }
}

export const HOST_MSG_APPLIED: Record<HostMsg["_tag"], true> = {
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
  restoreTranscript: true,
}

const _lock: typeof HOST_MSG_HANDLED = HOST_MSG_APPLIED
void _lock
