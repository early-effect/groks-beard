import { Schema } from "effect"
import {
  canStoreSnapshot,
  ChangeSetIndex,
  ChangeSetRecord,
  changeSetLineStats,
  type FileChange,
  type FileChangeRecord,
  fileChangeFromRecord,
  fileChangeFromReconstructed,
  formatLineStats,
  MISSING_SNAPSHOT_REASON,
  recordFromFileChange,
  type ReconstructedFileDiff,
  reconstructToolDiffs,
  resolveUndo,
  snapshotBytesFor,
  storedSnapshotBudget,
  type UndoMutation,
  type UndoResolution
} from "@groks-beard/core"

export const CHANGE_INDEX_KEY = "groksBeard.changeIndex"

export type ChangeStoreFs = {
  readonly read: (absPath: string) => string | undefined
  readonly write: (absPath: string, text: string) => void
  readonly remove: (absPath: string) => void
  readonly mkdirp: (absPath: string) => void
}

export type ChangeStoreDeps = {
  readonly storageRoot: string
  readonly fs: ChangeStoreFs
  readonly now: () => number
  readonly loadIndex: () => unknown
  readonly saveIndex: (index: ReadonlyArray<ChangeSetRecord>) => void
  readonly join: (...parts: string[]) => string
}

export type UndoApplyPorts = {
  readonly readDisk: (path: string) => string | undefined
  readonly confirmDirty: (path: string) => Promise<boolean>
  readonly apply: (mutations: ReadonlyArray<UndoMutation>) => Promise<void>
}

export type UndoApplyResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly path: string; readonly reason: string; readonly cancelled?: boolean }

const decodeIndex = (raw: unknown): Array<ChangeSetRecord> => {
  const decoded = Schema.decodeUnknownExit(ChangeSetIndex)(raw)
  return decoded._tag === "Success" ? [...decoded.value] : []
}

const safeSegment = (id: string): string => id.replace(/[/\\]/g, "_")

export const snapshotRelPath = (
  sessionId: string,
  turnId: string,
  filePath: string,
  side: "old" | "new"
): string => `${safeSegment(sessionId)}/${safeSegment(turnId)}/${encodeURIComponent(filePath)}.${side}`

export class ChangeStore {
  private sets: Array<ChangeSetRecord>
  private readonly listeners: Array<() => void> = []

  constructor(private readonly deps: ChangeStoreDeps) {
    this.sets = decodeIndex(deps.loadIndex())
  }

  onChange(listener: () => void): () => void {
    this.listeners.push(listener)
    return () => {
      const idx = this.listeners.indexOf(listener)
      if (idx >= 0) this.listeners.splice(idx, 1)
    }
  }

  list(): ReadonlyArray<ChangeSetRecord> {
    return [...this.sets].sort((a, b) => b.createdAt - a.createdAt)
  }

  pendingStats(): { readonly additions: number; readonly deletions: number } {
    return changeSetLineStats(this.list().flatMap((set) => set.files))
  }

  undoReason(): string | undefined {
    for (const set of this.sets) {
      for (const file of set.files) {
        if (file.undoDisabledReason !== undefined) return file.undoDisabledReason
      }
    }
    return undefined
  }

  statusText(): string {
    const stats = this.pendingStats()
    const reason = this.undoReason()
    const counts = formatLineStats(stats.additions, stats.deletions)
    return reason !== undefined ? `${counts} (${reason})` : counts
  }

  getTurn(sessionId: string, turnId: string): ChangeSetRecord | undefined {
    return this.sets.find((set) => set.sessionId === sessionId && set.turnId === turnId)
  }

  getFile(sessionId: string, turnId: string, path: string): FileChangeRecord | undefined {
    return this.getTurn(sessionId, turnId)?.files.find((file) => file.path === path)
  }

  ingestReconstructed(input: {
    readonly sessionId: string
    readonly turnId: string
    readonly title: string
    readonly diffs: ReadonlyArray<ReconstructedFileDiff>
  }): void {
    if (input.diffs.length === 0) return
    let set = this.sets.find((row) => row.sessionId === input.sessionId && row.turnId === input.turnId)
    if (set === undefined) {
      set = new ChangeSetRecord({
        sessionId: input.sessionId,
        turnId: input.turnId,
        title: input.title === "" ? "Untitled" : input.title,
        files: [],
        createdAt: this.deps.now()
      })
      this.sets.push(set)
    } else if (input.title !== "" && (set.title === "" || set.title === "Untitled")) {
      set = new ChangeSetRecord({ ...set, title: input.title })
      this.replaceSet(set)
    }
    for (const diff of input.diffs) {
      this.upsertFile(set, fileChangeFromReconstructed(diff))
      set = this.getTurn(input.sessionId, input.turnId) ?? set
    }
    this.persist()
  }

  ingestToolCall(input: {
    readonly sessionId: string
    readonly turnId: string
    readonly title: string
    readonly toolCall: unknown
    readonly diskIsBefore: boolean
    readonly readDisk: (path: string) => string | undefined
  }): ReadonlyArray<ReconstructedFileDiff> {
    const diffs = reconstructToolDiffs({
      toolCall: input.toolCall,
      diskText: input.readDisk,
      diskIsBefore: input.diskIsBefore
    })
    this.ingestReconstructed({
      sessionId: input.sessionId,
      turnId: input.turnId,
      title: input.title,
      diffs
    })
    return diffs
  }

  dropToolCall(toolCallId: string): void {
    let changed = false
    this.sets = this.sets.map((set) => {
      const kept = set.files.filter((file) => file.toolCallId !== toolCallId)
      if (kept.length === set.files.length) return set
      changed = true
      for (const file of set.files) {
        if (file.toolCallId === toolCallId) this.removeSnapshots(set, file)
      }
      return new ChangeSetRecord({ ...set, files: kept })
    }).filter((set) => set.files.length > 0)
    if (changed) this.persist()
  }

  keep(sessionId: string, turnId: string, path: string): void {
    const set = this.getTurn(sessionId, turnId)
    if (set === undefined) return
    const file = set.files.find((row) => row.path === path)
    if (file !== undefined) this.removeSnapshots(set, file)
    this.replaceSet(new ChangeSetRecord({
      ...set,
      files: set.files.filter((row) => row.path !== path)
    }))
    this.dropEmpty()
    this.persist()
  }

  keepAll(sessionId: string, turnId: string): void {
    const set = this.getTurn(sessionId, turnId)
    if (set === undefined) return
    for (const file of set.files) this.removeSnapshots(set, file)
    this.replaceSet(new ChangeSetRecord({ ...set, files: [] }))
    this.dropEmpty()
    this.persist()
  }

  keepEvery(): void {
    for (const set of [...this.sets]) {
      for (const file of set.files) this.removeSnapshots(set, file)
    }
    this.sets = []
    this.persist()
  }

  loadChange(sessionId: string, turnId: string, path: string): FileChange | undefined {
    const set = this.getTurn(sessionId, turnId)
    const record = set?.files.find((file) => file.path === path)
    if (set === undefined || record === undefined) return undefined
    if (!record.snapshotStored) return fileChangeFromRecord(record)
    const oldText = this.deps.fs.read(this.snapshotAbs(set, record.path, "old"))
    const newText = this.deps.fs.read(this.snapshotAbs(set, record.path, "new"))
    return fileChangeFromRecord(record, {
      ...(oldText !== undefined ? { oldText } : {}),
      ...(newText !== undefined ? { newText } : {})
    })
  }

  async undo(
    sessionId: string,
    turnId: string,
    path: string,
    ports: UndoApplyPorts
  ): Promise<UndoApplyResult> {
    const change = this.loadChange(sessionId, turnId, path)
    if (change === undefined) return { ok: false, path, reason: MISSING_SNAPSHOT_REASON }
    const resolved = await resolveUndo(
      change,
      ports.readDisk(path),
      () => ports.confirmDirty(path)
    )
    return this.finishUndo(sessionId, turnId, path, resolved, ports)
  }

  async undoAll(sessionId: string, turnId: string, ports: UndoApplyPorts): Promise<UndoApplyResult> {
    const set = this.getTurn(sessionId, turnId)
    if (set === undefined) return { ok: true }
    for (const file of [...set.files]) {
      const result = await this.undo(sessionId, turnId, file.path, ports)
      if (!result.ok) return result
    }
    return { ok: true }
  }

  async undoEvery(ports: UndoApplyPorts): Promise<UndoApplyResult> {
    for (const set of this.list()) {
      const result = await this.undoAll(set.sessionId, set.turnId, ports)
      if (!result.ok) return result
    }
    return { ok: true }
  }

  private async finishUndo(
    sessionId: string,
    turnId: string,
    path: string,
    resolved: UndoResolution,
    ports: UndoApplyPorts
  ): Promise<UndoApplyResult> {
    if (resolved._tag === "disabled") return { ok: false, path, reason: resolved.reason }
    if (resolved._tag === "cancelled") {
      return { ok: false, path, reason: "cancelled", cancelled: true }
    }
    await ports.apply(resolved.mutations)
    this.keep(sessionId, turnId, path)
    return { ok: true }
  }

  private upsertFile(set: ChangeSetRecord, change: FileChange): void {
    const existing = set.files.find((file) => file.path === change.path)
    if (existing?.snapshotStored === true) {
      this.removeSnapshots(set, existing)
    }
    const extra = snapshotBytesFor(change)
    const budget = storedSnapshotBudget(this.sets.map((row) =>
      row.sessionId === set.sessionId && row.turnId === set.turnId
        ? new ChangeSetRecord({
          ...row,
          files: row.files.filter((file) => file.path !== change.path)
        })
        : row
    ))
    const storeBody = canStoreSnapshot(budget.files, budget.bytes, extra)
    if (storeBody) {
      this.writeSnapshots(set, change)
    }
    const record = recordFromFileChange(change, {
      snapshotStored: storeBody,
      snapshotBytes: storeBody ? extra : 0
    })
    const files = [...set.files.filter((file) => file.path !== change.path), record]
    this.replaceSet(new ChangeSetRecord({ ...set, files }))
  }

  private writeSnapshots(set: ChangeSetRecord, change: FileChange): void {
    const dir = this.deps.join(
      this.deps.storageRoot,
      safeSegment(set.sessionId),
      safeSegment(set.turnId)
    )
    this.deps.fs.mkdirp(dir)
    if (change.oldSnapshot !== undefined) {
      this.deps.fs.write(this.snapshotAbs(set, change.path, "old"), change.oldSnapshot)
    }
    if (change.newSnapshot !== undefined) {
      this.deps.fs.write(this.snapshotAbs(set, change.path, "new"), change.newSnapshot)
    }
  }

  private removeSnapshots(set: ChangeSetRecord, file: FileChangeRecord): void {
    this.deps.fs.remove(this.snapshotAbs(set, file.path, "old"))
    this.deps.fs.remove(this.snapshotAbs(set, file.path, "new"))
  }

  private snapshotAbs(set: ChangeSetRecord, filePath: string, side: "old" | "new"): string {
    return this.deps.join(
      this.deps.storageRoot,
      snapshotRelPath(set.sessionId, set.turnId, filePath, side)
    )
  }

  private replaceSet(next: ChangeSetRecord): void {
    const idx = this.sets.findIndex((row) =>
      row.sessionId === next.sessionId && row.turnId === next.turnId
    )
    if (idx === -1) this.sets.push(next)
    else this.sets[idx] = next
  }

  private dropEmpty(): void {
    this.sets = this.sets.filter((set) => set.files.length > 0)
  }

  private persist(): void {
    this.deps.saveIndex(this.sets)
    for (const listener of this.listeners) listener()
  }
}
