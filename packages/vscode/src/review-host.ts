import {
  decodeSessionUpdate,
  diffsFromToolCall,
  diskIsBeforeFromStatus,
  isRejectPermissionKind,
  permissionOptionKind,
  type ReconstructedFileDiff,
  reconstructToolDiffs,
  toolCallFromPermissionParams,
  ToolCallUpdate,
  updateFromParams,
} from "@groks-beard/core"
import {
  ChangeStore,
  SIDECAR_SESSION_ID,
  type UndoApplyPorts,
  type UndoApplyResult,
} from "./change-store.js"
import { type DiffOpenPlan, diffTitle, planDiffOpen } from "./diff-open.js"
import { type FollowAlongPlan, planFollowAlong } from "./follow-along.js"
import { NO_BEARD_SNAPSHOT_NOTICE, resolvePathDiff } from "./path-diff.js"
import { BeardDocStore } from "./virtual-docs.js"

export type ReviewHostPorts = {
  readonly readDisk: (path: string) => string | undefined
  readonly hasChangesCommand: () => Promise<boolean>
  readonly openDiffs: (plan: DiffOpenPlan) => Promise<void>
  readonly follow: (plan: FollowAlongPlan) => void
  readonly warn: (message: string) => void
  readonly activeScheme: () => string | undefined
  readonly inDiffEditor: () => boolean
  readonly gitHead?: (path: string) => string | undefined
}

export class ReviewHost {
  private readonly permissions = new Map<string, unknown>()
  private hasMulti: boolean | undefined
  private lastSessionId = ""
  private readonly turns = new Map<string, { turnId: string; title: string }>()

  constructor(
    readonly docs: BeardDocStore,
    readonly store: ChangeStore,
    private readonly ports: ReviewHostPorts,
  ) {}

  rememberPermission(requestId: string, params: unknown): void {
    this.permissions.set(requestId, params)
    const toolCall = toolCallFromPermissionParams(params)
    const sessionId = sessionIdOf(params) ?? this.lastSessionId
    const diffs = reconstructToolDiffs({
      toolCall,
      diskText: this.ports.readDisk,
      diskIsBefore: true,
    })
    this.stageDocs(diffs)
    if (sessionId !== "") {
      this.store.ingestReconstructed({
        sessionId,
        turnId: this.currentTurn(sessionId).turnId,
        title: this.currentTurn(sessionId).title,
        diffs,
      })
    }
  }

  setTurn(sessionId: string, turnId: string, title: string): void {
    this.lastSessionId = sessionId
    this.turns.set(sessionId, { turnId, title })
  }

  private currentTurn(sessionId: string): { turnId: string; title: string } {
    return this.turns.get(sessionId) ?? { turnId: "turn_0", title: "Untitled" }
  }

  async openPermissionDiff(requestId: string): Promise<void> {
    const params = this.permissions.get(requestId)
    if (params === undefined) {
      this.ports.warn("No pending edit to diff.")
      return
    }
    const diffs = reconstructToolDiffs({
      toolCall: toolCallFromPermissionParams(params),
      diskText: this.ports.readDisk,
      diskIsBefore: true,
    })
    await this.openReconstructed(diffs, "Review edits")
  }

  async openFileDiff(sessionId: string, turnId: string, path: string): Promise<void> {
    if (sessionId === SIDECAR_SESSION_ID) {
      await this.openResolvedPath(path)
      return
    }
    const change = this.store.loadChange(sessionId, turnId, path)
    if (change === undefined) {
      this.ports.warn("No pending change for that file.")
      return
    }
    const original = change.oldSnapshot ?? ""
    const proposed = change.newSnapshot ?? this.ports.readDisk(path) ?? ""
    this.docs.setPair(path, original, proposed)
    await this.openReconstructed([{
      path,
      oldText: original,
      newText: proposed,
      firstChangedLine: 0,
      wholeFile: change.wholeFile,
      kind: change.kind,
      toolCallId: change.toolCallId,
      ...(change.fromPath !== undefined ? { fromPath: change.fromPath } : {}),
    }], change.path)
  }

  ingestUpdate(params: unknown, ctx: {
    readonly sessionId: string
    readonly turnId: string
    readonly title: string
  }): void {
    this.setTurn(
      ctx.sessionId !== "" ? ctx.sessionId : sessionIdOf(params) ?? ctx.sessionId,
      ctx.turnId,
      ctx.title,
    )
    const decoded = decodeSessionUpdate(updateFromParams(params))
    if (!(decoded instanceof ToolCallUpdate)) return
    const extracted = diffsFromToolCall(decoded)
    if (extracted.locations.length > 0) {
      const scheme = this.ports.activeScheme()
      this.ports.follow(planFollowAlong(extracted.locations, {
        inDiffEditor: this.ports.inDiffEditor(),
        ...(scheme !== undefined ? { scheme } : {}),
      }))
    }
    if (extracted.status === "cancelled" || extracted.status === "failed") {
      this.store.dropToolCall(extracted.toolCallId)
      return
    }
    const sessionId = ctx.sessionId !== "" ? ctx.sessionId : sessionIdOf(params) ?? ""
    if (sessionId === "") return
    const diffs = this.store.ingestToolCall({
      sessionId,
      turnId: ctx.turnId,
      title: ctx.title,
      toolCall: decoded,
      diskIsBefore: diskIsBeforeFromStatus(extracted.status),
      readDisk: this.ports.readDisk,
    })
    this.stageDocs(diffs)
  }

  onPermissionChoice(requestId: string, optionId: string): void {
    const params = this.permissions.get(requestId)
    if (params === undefined) return
    this.permissions.delete(requestId)
    if (isRejectPermissionKind(permissionOptionKind(params, optionId))) {
      const extracted = diffsFromToolCall(toolCallFromPermissionParams(params))
      this.store.dropToolCall(extracted.toolCallId)
    }
  }

  cancelPendingPermissions(): void {
    for (const params of this.permissions.values()) {
      const extracted = diffsFromToolCall(toolCallFromPermissionParams(params))
      this.store.dropToolCall(extracted.toolCallId)
    }
    this.permissions.clear()
  }

  async openResolvedPath(
    path: string,
  ): Promise<{ readonly ok: true } | { readonly ok: false; readonly reason: string }> {
    const beard = this.store.loadStoredByPath(path)
    const disk = this.ports.readDisk(path)
    const gitHead = this.ports.gitHead?.(path)
    const resolved = resolvePathDiff({
      path,
      ...(beard !== undefined
        ? { beard: { original: beard.original, proposed: beard.proposed } }
        : {}),
      ...(gitHead !== undefined ? { gitHead } : {}),
      ...(disk !== undefined ? { disk } : {}),
    })
    if (!resolved.ok) {
      this.ports.warn(resolved.reason)
      return resolved
    }
    this.docs.setPair(path, resolved.original, resolved.proposed)
    if (resolved.notice !== undefined) this.ports.warn(resolved.notice)
    await this.openReconstructed([{
      path,
      oldText: resolved.original,
      newText: resolved.proposed,
      firstChangedLine: 0,
      wholeFile: true,
      kind: "modify",
      toolCallId: "sidecar",
    }], resolved.source === "disk" ? `${path} (${NO_BEARD_SNAPSHOT_NOTICE})` : path)
    return { ok: true }
  }

  keep(sessionId: string, turnId: string, path: string): void {
    this.store.keep(sessionId, turnId, path)
  }

  keepAll(sessionId: string, turnId: string): void {
    this.store.keepAll(sessionId, turnId)
  }

  async undo(
    sessionId: string,
    turnId: string,
    path: string,
    ports: UndoApplyPorts,
  ): Promise<UndoApplyResult> {
    return this.store.undo(sessionId, turnId, path, ports)
  }

  async undoAll(
    sessionId: string,
    turnId: string,
    ports: UndoApplyPorts,
  ): Promise<UndoApplyResult> {
    return this.store.undoAll(sessionId, turnId, ports)
  }

  private stageDocs(diffs: ReadonlyArray<ReconstructedFileDiff>): void {
    for (const diff of diffs) this.docs.setPair(diff.path, diff.oldText, diff.newText)
  }

  private async openReconstructed(
    diffs: ReadonlyArray<ReconstructedFileDiff>,
    fallbackTitle: string,
  ): Promise<void> {
    if (diffs.length === 0) {
      this.ports.warn("No diff content on this request.")
      return
    }
    this.stageDocs(diffs)
    const wholeFile = diffs.every((diff) => diff.wholeFile)
    const title = diffTitle(fallbackTitle, wholeFile)
    const hasMulti = await this.multiDiffAvailable()
    const plan = planDiffOpen(hasMulti, title, diffs.map((diff) => diff.path))
    try {
      await this.ports.openDiffs(plan)
    } catch {
      if (plan.mode === "multi") {
        try {
          await this.ports.openDiffs(planDiffOpen(false, title, diffs.map((diff) => diff.path)))
          return
        } catch {
          this.ports.warn("Could not open a diff editor.")
        }
      } else {
        this.ports.warn("Could not open a diff editor.")
      }
    }
  }

  private async multiDiffAvailable(): Promise<boolean> {
    if (this.hasMulti !== undefined) return this.hasMulti
    this.hasMulti = await this.ports.hasChangesCommand()
    return this.hasMulti
  }
}

const sessionIdOf = (params: unknown): string | undefined => {
  if (typeof params !== "object" || params === null) return undefined
  const sessionId = (params as { sessionId?: unknown }).sessionId
  return typeof sessionId === "string" ? sessionId : undefined
}
