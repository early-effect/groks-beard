import {
  type BeardAcp,
  cancelSession,
  newSession,
  type PermissionOutcome,
  promptSession,
  setSessionMode,
} from "@groks-beard/acp"
import {
  displayStopReason,
  elicitCardFromParams,
  type HostMsg,
  hostMsgsFromSessionUpdate,
  permissionCardFromParams,
  PromptChip,
} from "@groks-beard/core"
import { Effect } from "effect"
import { ComposerState } from "./composer.js"

const DEFAULT_MODES = ["normal", "plan", "always-approve"] as const

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
}

export class ChatRuntime {
  sessionId: string | undefined
  modeId = "normal"
  availableModes: Array<string> = [...DEFAULT_MODES]
  currentTurnId = "turn_0"
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

  constructor(readonly deps: ChatRuntimeDeps) {}

  async attachSession(created: {
    readonly sessionId: string
    readonly modeId?: string
    readonly availableModes?: ReadonlyArray<string>
  }): Promise<void> {
    this.sessionId = created.sessionId
    if (created.modeId !== undefined) this.modeId = created.modeId
    if (created.availableModes !== undefined && created.availableModes.length > 0) {
      this.availableModes = [...created.availableModes]
    }
    this.postMeta()
  }

  async ensureSession(): Promise<string> {
    if (this.sessionId !== undefined) return this.sessionId
    const created = await Effect.runPromise(newSession(this.deps.agent, this.deps.cwd))
    await this.attachSession(created)
    return created.sessionId
  }

  onSessionUpdate(params: unknown): void {
    for (const msg of hostMsgsFromSessionUpdate(params, this.currentTurnId)) {
      if (msg._tag === "sessionMeta") {
        if (msg.modeId !== "") this.modeId = msg.modeId
        this.deps.post({
          _tag: "sessionMeta",
          sessionId: msg.sessionId !== "" ? msg.sessionId : this.sessionId ?? "",
          title: msg.title !== "" ? msg.title : this.title,
          modeId: msg.modeId !== "" ? msg.modeId : this.modeId,
          ...(msg.modelId !== undefined ? { modelId: msg.modelId } : {}),
          ...(msg.occupancy !== undefined ? { occupancy: msg.occupancy } : {}),
        })
        continue
      }
      this.deps.post(msg)
    }
  }

  onPermission(params: unknown, requestId: string): Promise<PermissionOutcome> {
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

  async cancel(): Promise<void> {
    for (const resolve of this.pendingPerm.values()) {
      resolve({ outcome: { outcome: "cancelled" } })
    }
    this.pendingPerm.clear()
    const sessionId = this.sessionId
    if (sessionId !== undefined) {
      await Effect.runPromise(cancelSession(this.deps.agent, sessionId))
    }
  }

  permissionChoice(requestId: string, optionId: string): void {
    const resolve = this.pendingPerm.get(requestId)
    if (resolve === undefined) return
    this.pendingPerm.delete(requestId)
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
    const sessionId = await this.ensureSession()
    const modes = this.availableModes.length > 0 ? this.availableModes : [...DEFAULT_MODES]
    const idx = modes.indexOf(this.modeId)
    const next = modes[(idx + 1) % modes.length] ?? "normal"
    await Effect.runPromise(setSessionMode(this.deps.agent, sessionId, next))
    this.modeId = next
    this.postMeta()
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

  private postMeta(): void {
    this.deps.post({
      _tag: "sessionMeta",
      sessionId: this.sessionId ?? "",
      title: this.title,
      modeId: this.modeId,
    })
  }

  private nextTurnId(): string {
    this.turnSeq += 1
    this.currentTurnId = `turn_${this.turnSeq}`
    return this.currentTurnId
  }

  private async runTurn(text: string, chips: ReadonlyArray<PromptChip>): Promise<void> {
    const sessionId = await this.ensureSession()
    for (const chip of chips) this.deps.composer.addChip(chip)
    const turnId = this.nextTurnId()
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
      this.deps.post({
        _tag: "turnEnd",
        turnId,
        stopReason: displayStopReason(result.stopReason),
      })
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      this.deps.post({ _tag: "error", message })
      this.deps.post({ _tag: "turnEnd", turnId, stopReason: "unknown" })
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
