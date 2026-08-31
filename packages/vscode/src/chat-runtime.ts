import {
  type BeardAcp,
  cancelSession,
  newSession,
  type PermissionOutcome,
  promptSession,
  reloadSessionMcp,
  setSessionConfigOption,
  setSessionMode,
  setSessionModel,
} from "@groks-beard/acp"
import {
  contextWindowFor,
  displayStopReason,
  elicitCardFromParams,
  FALLBACK_REASONING_OPTIONS,
  type HostMsg,
  hostMsgsFromSessionUpdate,
  occupancyFromUnknown,
  overlayModelCatalog,
  permissionCardFromParams,
  PromptChip,
  sessionIdFromParams,
  type SessionModelOption,
  type SessionModeOption,
  turnTitleFromPrompt,
} from "@groks-beard/core"
import { Effect } from "effect"
import { ComposerState } from "./composer.js"
import { projectHttpMcpUrlsFor, waitForHttpMcpWithBackoff } from "./project-mcp.js"

export const DEFAULT_MODES: ReadonlyArray<SessionModeOption> = [
  { id: "normal", name: "Normal" },
  { id: "auto", name: "Auto" },
  { id: "plan", name: "Plan" },
  { id: "always-approve", name: "Always approve" },
]

export const mergeSessionModes = (
  advertised: ReadonlyArray<SessionModeOption> | undefined,
): Array<SessionModeOption> => {
  const names = new Map(DEFAULT_MODES.map((mode) => [mode.id, mode.name]))
  if (advertised !== undefined) {
    for (const mode of advertised) {
      if (mode.id === "") continue
      if (mode.name !== "") names.set(mode.id, mode.name)
    }
  }
  const extras = (advertised ?? []).filter((mode) =>
    mode.id !== "" && DEFAULT_MODES.every((known) => known.id !== mode.id)
  )
  return [
    ...DEFAULT_MODES.map((mode) => ({
      id: mode.id,
      name: names.get(mode.id) ?? mode.name,
    })),
    ...extras.map((mode) => ({ id: mode.id, name: mode.name !== "" ? mode.name : mode.id })),
  ]
}

export type ChatRuntimeDeps = {
  readonly agent: BeardAcp["agent"]
  readonly post: (msg: HostMsg) => void
  readonly composer: ComposerState
  readonly cwd: string
  readonly includeActiveFileByDefault: () => boolean
  readonly activeFile?: () => PromptChip | undefined
  readonly searchFiles?: (
    query: string,
  ) => Promise<ReadonlyArray<{ path: string; absPath: string }>>
  readonly openChanges?: (turnId?: string) => void
  readonly openDiff?: (requestId: string) => void
  readonly onTurn?: (sessionId: string, turnId: string, title: string) => void
  readonly rememberPermission?: (requestId: string, params: unknown) => void
  readonly ingestUpdate?: (
    params: unknown,
    ctx: { readonly sessionId: string; readonly turnId: string; readonly title: string },
  ) => void
  readonly onPermissionChoice?: (requestId: string, optionId: string) => void
  readonly onCancelPermissions?: () => void
  readonly onMcpIssue?: (message: string) => void
  readonly modelCatalog?: () => unknown
}

export class ChatRuntime {
  sessionId: string | undefined
  modeId = "normal"
  modelId: string | undefined
  availableModes: Array<SessionModeOption> = [...DEFAULT_MODES]
  availableModels: Array<SessionModelOption> = []
  currentTurnId = "turn_0"
  currentTurnTitle = "Untitled"
  private turnSeq = 0
  private running = false
  private readonly queue: Array<{ text: string; chips: ReadonlyArray<PromptChip> }> = []
  private readonly pendingPerm = new Map<string, (outcome: PermissionOutcome) => void>()
  private readonly pendingPlan = new Map<
    string,
    (outcome: { outcome: "approved" | "cancelled" | "abandoned"; comment?: string }) => void
  >()
  private readonly pendingQuestion = new Map<string, (result: unknown) => void>()
  private readonly pendingElicit = new Map<
    string,
    (result: { action: "accept" | "decline" | "cancel" }) => void
  >()
  private title = ""
  private occupancy: { used: number; size: number } | undefined
  private reasoning: NonNullable<Extract<HostMsg, { _tag: "sessionMeta" }>["reasoning"]> | undefined
  private projectHttpMcpReady = false
  private mcpIssueReported = false

  constructor(readonly deps: ChatRuntimeDeps) {}

  async attachSession(created: {
    readonly sessionId: string
    readonly modeId?: string
    readonly availableModes?: ReadonlyArray<SessionModeOption>
    readonly modelId?: string
    readonly availableModels?: ReadonlyArray<SessionModelOption>
    readonly reasoning?: NonNullable<Extract<HostMsg, { _tag: "sessionMeta" }>["reasoning"]>
  }): Promise<void> {
    this.sessionId = created.sessionId
    if (created.modeId !== undefined) this.modeId = created.modeId
    this.availableModes = mergeSessionModes(created.availableModes)
    if (created.modelId !== undefined) this.modelId = created.modelId
    if (created.availableModels !== undefined && created.availableModels.length > 0) {
      this.availableModels = overlayModelCatalog(
        created.availableModels,
        this.deps.modelCatalog?.(),
      )
    }
    if (created.reasoning !== undefined) this.reasoning = created.reasoning
    this.syncReasoningWithModel()
    this.applyModelWindow()
    this.postMeta()
  }

  async ensureSession(): Promise<string> {
    if (this.sessionId !== undefined) return this.sessionId
    const created = await Effect.runPromise(newSession(this.deps.agent, this.deps.cwd))
    await this.attachSession(created)
    return created.sessionId
  }

  async refreshMcp(name?: string): Promise<"ok" | "respawn"> {
    const sessionId = await this.ensureSession()
    const urls = projectHttpMcpUrlsFor(this.deps.cwd, name)
    if (urls.length > 0) {
      const up = await waitForHttpMcpWithBackoff(urls)
      if (up === undefined) {
        this.reportMcpIssue()
        return "ok"
      }
    }
    if (await reloadSessionMcp(this.deps.agent, sessionId, name)) {
      this.projectHttpMcpReady = true
      this.mcpIssueReported = false
      return "ok"
    }
    if (urls.length > 0 && this.turnSeq === 0) return "respawn"
    if (urls.length > 0) this.reportMcpIssue()
    return "ok"
  }

  async prepareMcpForTurn(): Promise<"ok" | "respawn"> {
    if (this.projectHttpMcpReady) return "ok"
    if (projectHttpMcpUrlsFor(this.deps.cwd).length === 0) return "ok"
    return this.refreshMcp()
  }

  onSessionUpdate(params: unknown): void {
    this.deps.ingestUpdate?.(params, {
      sessionId: this.sessionId ?? sessionIdFromParams(params) ?? "",
      turnId: this.currentTurnId,
      title: this.currentTurnTitle,
    })
    const occupancy = occupancyFromUnknown(params, this.modelWindow())
    if (occupancy !== undefined) this.rememberOccupancy(occupancy)
    for (const msg of hostMsgsFromSessionUpdate(params, this.currentTurnId)) {
      if (msg._tag === "sessionMeta") {
        if (msg.modeId !== "") this.modeId = msg.modeId
        if (msg.modelId !== undefined) this.modelId = msg.modelId
        if (msg.occupancy !== undefined) this.rememberOccupancy(msg.occupancy)
        if (msg.reasoning !== undefined) this.reasoning = msg.reasoning
        this.postMeta()
        continue
      }
      this.deps.post(msg)
    }
    if (occupancy !== undefined) this.postMeta()
  }

  onPermission(params: unknown, requestId: string): Promise<PermissionOutcome> {
    this.deps.rememberPermission?.(requestId, params)
    this.deps.post(permissionCardFromParams(params, requestId))
    return new Promise((resolve) => {
      this.pendingPerm.set(requestId, resolve)
    })
  }

  onExitPlanMode(
    params: unknown,
    requestId: string,
  ): Promise<{ outcome: "approved" | "cancelled" | "abandoned"; comment?: string }> {
    const rec = typeof params === "object" && params !== null
      ? params as Record<string, unknown>
      : {}
    const planMarkdown = typeof rec.planContent === "string"
      ? rec.planContent
      : typeof rec.planMarkdown === "string"
      ? rec.planMarkdown
      : ""
    this.deps.post({ _tag: "planCard", requestId, planMarkdown })
    return new Promise((resolve) => {
      this.pendingPlan.set(requestId, resolve)
    })
  }

  onAskUserQuestion(params: unknown, requestId: string): Promise<unknown> {
    const rec = typeof params === "object" && params !== null
      ? params as Record<string, unknown>
      : {}
    const questions = Array.isArray(rec.questions) ? rec.questions : []
    this.deps.post({
      _tag: "questionCard",
      requestId,
      questions: questions as never,
    })
    return new Promise((resolve) => {
      this.pendingQuestion.set(requestId, resolve)
    })
  }

  onElicit(
    params: unknown,
    requestId: string,
  ): Promise<{ action: "accept" | "decline" | "cancel" }> {
    this.deps.post(elicitCardFromParams(params, requestId))
    return new Promise((resolve) => {
      this.pendingElicit.set(requestId, resolve)
    })
  }

  async send(text: string, chips: ReadonlyArray<PromptChip>): Promise<void> {
    if (this.running) {
      this.queue.push({ text, chips })
      this.deps.post({ _tag: "queued", count: this.queue.length })
      return
    }
    await this.runTurn(text, chips)
  }

  async queueFollowUp(text: string, chips: ReadonlyArray<PromptChip>): Promise<void> {
    this.queue.push({ text, chips })
    this.deps.post({ _tag: "queued", count: this.queue.length })
  }

  async sendQueuedNow(): Promise<void> {
    if (this.queue.length === 0) return
    if (this.running) {
      const sessionId = this.sessionId
      if (sessionId !== undefined) {
        await Effect.runPromise(cancelSession(this.deps.agent, sessionId))
      }
      return
    }
    const next = this.queue.shift()
    if (next === undefined) return
    this.deps.post({ _tag: "queued", count: this.queue.length })
    await this.runTurn(next.text, next.chips)
  }

  async cancel(): Promise<void> {
    this.queue.length = 0
    this.deps.post({ _tag: "queued", count: 0 })
    for (const resolve of this.pendingPerm.values()) {
      resolve({ outcome: { outcome: "cancelled" } })
    }
    this.pendingPerm.clear()
    this.deps.onCancelPermissions?.()
    const sessionId = this.sessionId
    if (sessionId !== undefined) {
      await Effect.runPromise(cancelSession(this.deps.agent, sessionId))
    }
  }

  permissionChoice(requestId: string, optionId: string): void {
    const resolve = this.pendingPerm.get(requestId)
    if (resolve === undefined) return
    this.pendingPerm.delete(requestId)
    this.deps.onPermissionChoice?.(requestId, optionId)
    resolve({ outcome: { outcome: "selected", optionId } })
  }

  permissionPark(_requestId: string): void {
    return
  }

  planVerdict(
    requestId: string,
    verdict: "approved" | "cancelled" | "abandoned",
    comment?: string,
  ): void {
    const resolve = this.pendingPlan.get(requestId)
    if (resolve === undefined) return
    this.pendingPlan.delete(requestId)
    resolve(comment !== undefined ? { outcome: verdict, comment } : { outcome: verdict })
  }

  questionChoice(requestId: string, answers: unknown): void {
    const resolve = this.pendingQuestion.get(requestId)
    if (resolve === undefined) return
    this.pendingQuestion.delete(requestId)
    resolve({ answers })
  }

  questionDismiss(requestId: string): void {
    const resolve = this.pendingQuestion.get(requestId)
    if (resolve === undefined) return
    this.pendingQuestion.delete(requestId)
    resolve({ answers: [] })
  }

  elicitAccept(requestId: string): void {
    const resolve = this.pendingElicit.get(requestId)
    if (resolve === undefined) return
    this.pendingElicit.delete(requestId)
    resolve({ action: "accept" })
  }

  elicitDecline(requestId: string): void {
    const resolve = this.pendingElicit.get(requestId)
    if (resolve === undefined) return
    this.pendingElicit.delete(requestId)
    resolve({ action: "decline" })
  }

  async cycleMode(): Promise<void> {
    const modes = this.availableModes.length > 0 ? this.availableModes : [...DEFAULT_MODES]
    const ids = modes.map((mode) => mode.id)
    const idx = ids.indexOf(this.modeId)
    const next = ids[(idx + 1) % ids.length] ?? "normal"
    await this.setMode(next)
  }

  async setMode(modeId: string): Promise<void> {
    if (modeId === this.modeId && this.sessionId !== undefined) {
      this.postMeta()
      return
    }
    try {
      const sessionId = await this.ensureSession()
      await Effect.runPromise(setSessionMode(this.deps.agent, sessionId, modeId))
      this.modeId = modeId
      this.postMeta()
    } catch (cause) {
      this.deps.post({
        _tag: "error",
        message: cause instanceof Error ? cause.message : String(cause),
      })
    }
  }

  async setModel(modelId: string): Promise<boolean> {
    if (modelId === this.modelId && this.sessionId !== undefined) {
      this.postMeta()
      return true
    }
    try {
      const sessionId = await this.ensureSession()
      await Effect.runPromise(setSessionModel(this.deps.agent, sessionId, modelId))
      this.modelId = modelId
      this.syncReasoningWithModel()
      this.applyModelWindow()
      this.postMeta()
      return true
    } catch (cause) {
      this.deps.post({
        _tag: "error",
        message: cause instanceof Error ? cause.message : String(cause),
      })
      return false
    }
  }

  async mentionQuery(query: string): Promise<void> {
    const files = await this.deps.searchFiles?.(query) ?? []
    this.deps.post({ _tag: "mentionResults", query, files: [...files] })
  }

  mentionPick(path: string, absPath: string): void {
    this.deps.composer.addChip(
      new PromptChip({ path, absPath, source: "mention" }),
    )
  }

  openChanges(turnId?: string): void {
    this.deps.openChanges?.(turnId)
  }

  openDiff(requestId: string): void {
    this.deps.openDiff?.(requestId)
  }

  async setReasoning(value: string, modelId?: string): Promise<void> {
    if (modelId !== undefined && modelId !== "" && modelId !== this.modelId) {
      if (!await this.setModel(modelId)) return
    }
    const id = this.reasoning?.id ?? "reasoning_effort"
    try {
      const sessionId = await this.ensureSession()
      await Effect.runPromise(setSessionConfigOption(this.deps.agent, sessionId, id, value))
      const advertised = this.availableModels.find((model) => model.modelId === this.modelId)
      const options = advertised?.reasoning?.options ?? this.reasoning?.options ?? [
        ...FALLBACK_REASONING_OPTIONS,
      ]
      this.reasoning = { id, current: value, options: [...options] }
      this.patchModelReasoning(this.modelId, value)
      this.postMeta()
    } catch (cause) {
      this.deps.post({
        _tag: "error",
        message: cause instanceof Error ? cause.message : String(cause),
      })
    }
  }

  private rememberOccupancy(occupancy: { used: number; size: number }): void {
    const window = this.modelWindow()
    const size = window !== undefined && window > occupancy.size ? window : occupancy.size
    const used = occupancy.used > 0 ? occupancy.used : this.occupancy?.used ?? 0
    this.occupancy = { used, size }
  }

  private modelWindow(): number | undefined {
    const advertised = this.availableModels.find((model) => model.modelId === this.modelId)
    return contextWindowFor(this.modelId, advertised?.contextWindow)
  }

  private syncReasoningWithModel(): void {
    const advertised = this.availableModels.find((model) => model.modelId === this.modelId)
    if (advertised?.reasoning === undefined) return
    const options = advertised.reasoning.options
    const previous = this.reasoning
    if (previous === undefined) {
      this.reasoning = {
        id: "reasoning_effort",
        current: advertised.reasoning.current,
        options: [...options],
      }
      return
    }
    const current = options.some((item) => item.value === previous.current)
      ? previous.current
      : advertised.reasoning.current
    this.reasoning = { id: previous.id, current, options: [...options] }
  }

  private patchModelReasoning(modelId: string | undefined, current: string): void {
    if (modelId === undefined) return
    this.availableModels = this.availableModels.map((model) => {
      if (model.modelId !== modelId) return model
      const options = model.reasoning?.options ?? this.reasoning?.options
      if (options === undefined || options.length === 0) return model
      return { ...model, reasoning: { current, options: [...options] } }
    })
  }

  private modelsForPost(): ReadonlyArray<SessionModelOption> {
    const reasoning = this.reasoning
    if (reasoning === undefined) return this.availableModels
    return this.availableModels.map((model) => {
      if (model.modelId !== this.modelId) return model
      const options = model.reasoning?.options ?? reasoning.options
      if (options.length === 0) return model
      return {
        ...model,
        reasoning: { current: reasoning.current, options: [...options] },
      }
    })
  }

  private applyModelWindow(): void {
    const window = this.modelWindow()
    if (window === undefined) return
    if (this.occupancy === undefined) {
      this.occupancy = { used: 0, size: window }
      return
    }
    this.occupancy = { used: this.occupancy.used, size: window }
  }

  private postMeta(): void {
    this.deps.post({
      _tag: "sessionMeta",
      sessionId: this.sessionId ?? "",
      title: this.title,
      modeId: this.modeId,
      ...(this.modelId !== undefined ? { modelId: this.modelId } : {}),
      ...(this.occupancy !== undefined ? { occupancy: this.occupancy } : {}),
      ...(this.reasoning !== undefined ? { reasoning: this.reasoning } : {}),
      ...(this.availableModes.length > 0 ? { availableModes: this.availableModes } : {}),
      ...(this.availableModels.length > 0 ? { availableModels: this.modelsForPost() } : {}),
    })
  }

  private nextTurnId(text: string): string {
    this.turnSeq += 1
    this.currentTurnId = `turn_${this.turnSeq}`
    this.currentTurnTitle = turnTitleFromPrompt(text)
    return this.currentTurnId
  }

  private reportMcpIssue(): void {
    if (this.mcpIssueReported) return
    this.mcpIssueReported = true
    this.deps.onMcpIssue?.(
      "Could not connect to project MCP servers. They may still be starting. Refresh a server in Settings, or send again once they are up.",
    )
  }

  private async runTurn(text: string, chips: ReadonlyArray<PromptChip>): Promise<void> {
    const sessionId = await this.ensureSession()
    for (const chip of chips) this.deps.composer.addChip(chip)
    const turnId = this.nextTurnId(text)
    this.deps.onTurn?.(sessionId, turnId, this.currentTurnTitle)
    this.running = true
    this.deps.post({ _tag: "userMessage", turnId, text, chips: [...chips] })
    const active = this.deps.activeFile?.()
    const prompt = this.deps.composer.promptText(
      text,
      this.deps.includeActiveFileByDefault(),
      ...(active !== undefined ? [active] : []),
    )
    this.deps.composer.clear()
    try {
      const result = await Effect.runPromise(promptSession(this.deps.agent, sessionId, prompt))
      await new Promise((resolve) => setTimeout(resolve, 0))
      if (result.occupancy !== undefined) {
        this.rememberOccupancy(result.occupancy)
        this.postMeta()
      }
      this.deps.post({
        _tag: "turnEnd",
        turnId,
        stopReason: displayStopReason(result.stopReason),
      })
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      const cancelled = /cancel/i.test(message)
      if (!cancelled) this.deps.post({ _tag: "error", message })
      this.deps.post({
        _tag: "turnEnd",
        turnId,
        stopReason: cancelled ? "cancelled" : "unknown",
      })
    } finally {
      this.running = false
    }
    const next = this.queue.shift()
    if (next !== undefined) {
      this.deps.post({ _tag: "queued", count: this.queue.length })
      await this.runTurn(next.text, next.chips)
    }
  }
}
