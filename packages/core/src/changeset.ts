import { Schema } from "effect"

export class FileChange extends Schema.Class<FileChange>("FileChange")({
  path: Schema.String,
  kind: Schema.Literals(["add", "modify", "delete", "move"]),
  fromPath: Schema.optionalKey(Schema.String),
  oldSnapshot: Schema.optionalKey(Schema.String),
  newSnapshot: Schema.optionalKey(Schema.String),
  additions: Schema.Number,
  deletions: Schema.Number,
  wholeFile: Schema.Boolean,
  toolCallId: Schema.String,
}) {}

export class ChangeSet extends Schema.Class<ChangeSet>("ChangeSet")({
  sessionId: Schema.String,
  turnId: Schema.String,
  title: Schema.String,
  files: Schema.Array(FileChange),
  createdAt: Schema.Number,
}) {}

export const SNAPSHOT_FILE_CAP = 32
export const SNAPSHOT_BYTE_CAP = 16 * 1024 * 1024
export const MISSING_SNAPSHOT_REASON = "missing snapshot"
export const REGION_ONLY_REASON = "region only"

export class FileChangeRecord extends Schema.Class<FileChangeRecord>("FileChangeRecord")({
  path: Schema.String,
  kind: Schema.Literals(["add", "modify", "delete", "move"]),
  fromPath: Schema.optionalKey(Schema.String),
  additions: Schema.Number,
  deletions: Schema.Number,
  wholeFile: Schema.Boolean,
  toolCallId: Schema.String,
  snapshotStored: Schema.Boolean,
  snapshotBytes: Schema.Number,
  undoDisabledReason: Schema.optionalKey(Schema.String),
}) {}

export class ChangeSetRecord extends Schema.Class<ChangeSetRecord>("ChangeSetRecord")({
  sessionId: Schema.String,
  turnId: Schema.String,
  title: Schema.String,
  files: Schema.Array(FileChangeRecord),
  createdAt: Schema.Number,
}) {}

export const ChangeSetIndex = Schema.Array(ChangeSetRecord)
export type ChangeSetIndex = typeof ChangeSetIndex.Type

export const canStoreSnapshot = (
  storedFiles: number,
  storedBytes: number,
  extraBytes: number,
): boolean => storedFiles < SNAPSHOT_FILE_CAP && storedBytes + extraBytes <= SNAPSHOT_BYTE_CAP

export const storedSnapshotBudget = (
  sets: ReadonlyArray<ChangeSetRecord>,
): { readonly files: number; readonly bytes: number } => {
  let files = 0
  let bytes = 0
  for (const set of sets) {
    for (const file of set.files) {
      if (!file.snapshotStored) continue
      files += 1
      bytes += file.snapshotBytes
    }
  }
  return { files, bytes }
}

export const changeSetLineStats = (
  files: ReadonlyArray<{ readonly additions: number; readonly deletions: number }>,
): { readonly additions: number; readonly deletions: number } =>
  files.reduce(
    (acc, file) => ({
      additions: acc.additions + file.additions,
      deletions: acc.deletions + file.deletions,
    }),
    { additions: 0, deletions: 0 },
  )

export const formatLineStats = (additions: number, deletions: number): string =>
  `+${additions}/\u2212${deletions}`

export const turnTitleFromPrompt = (text: string): string => {
  const line = text.split(/\r?\n/).map((row) => row.trim()).find((row) => row.length > 0)
  if (line === undefined) return "Untitled"
  return line.length > 80 ? `${line.slice(0, 77)}...` : line
}

export const fileChangeFromRecord = (
  record: FileChangeRecord,
  snapshots?: { readonly oldText?: string; readonly newText?: string },
): FileChange =>
  new FileChange({
    path: record.path,
    kind: record.kind,
    additions: record.additions,
    deletions: record.deletions,
    wholeFile: record.wholeFile,
    toolCallId: record.toolCallId,
    ...(record.fromPath !== undefined ? { fromPath: record.fromPath } : {}),
    ...(snapshots?.oldText !== undefined ? { oldSnapshot: snapshots.oldText } : {}),
    ...(snapshots?.newText !== undefined ? { newSnapshot: snapshots.newText } : {}),
  })

export const undoDisabledReasonFor = (
  change: { readonly kind: FileChange["kind"]; readonly wholeFile: boolean },
  snapshotStored: boolean,
): string | undefined => {
  if (!snapshotStored) return MISSING_SNAPSHOT_REASON
  if (change.kind === "modify" && !change.wholeFile) return REGION_ONLY_REASON
  return undefined
}

export const shouldKeepExistingFileChange = (
  existing: { readonly wholeFile: boolean; readonly snapshotStored: boolean },
  incoming: { readonly wholeFile: boolean; readonly regionStandIn?: boolean },
): boolean =>
  existing.snapshotStored
  && existing.wholeFile
  && (!incoming.wholeFile || incoming.regionStandIn === true)

export const recordFromFileChange = (
  change: FileChange,
  stored: { readonly snapshotStored: boolean; readonly snapshotBytes: number },
): FileChangeRecord => {
  const undoDisabledReason = undoDisabledReasonFor(change, stored.snapshotStored)
  return new FileChangeRecord({
    path: change.path,
    kind: change.kind,
    additions: change.additions,
    deletions: change.deletions,
    wholeFile: change.wholeFile,
    toolCallId: change.toolCallId,
    snapshotStored: stored.snapshotStored,
    snapshotBytes: stored.snapshotBytes,
    ...(change.fromPath !== undefined ? { fromPath: change.fromPath } : {}),
    ...(undoDisabledReason !== undefined ? { undoDisabledReason } : {}),
  })
}

export type UndoPlan =
  | { readonly _tag: "replace"; readonly path: string; readonly text: string }
  | { readonly _tag: "create"; readonly path: string; readonly text: string }
  | { readonly _tag: "delete"; readonly path: string; readonly confirmIfDirty: boolean }
  | {
    readonly _tag: "moveReverse"
    readonly fromPath: string
    readonly toPath: string
    readonly oldText: string
  }
  | { readonly _tag: "disabled"; readonly reason: string }

export const keepFile = (set: ChangeSet, path: string): ChangeSet =>
  new ChangeSet({
    ...set,
    files: set.files.filter((file) => file.path !== path),
  })

export const keepAll = (set: ChangeSet): ChangeSet => new ChangeSet({ ...set, files: [] })

const splitLines = (text: string): ReadonlyArray<string> => text.split(/\r?\n/)

export const lineDiffStats = (
  oldText: string,
  newText: string,
): { readonly additions: number; readonly deletions: number } => {
  const oldLines = splitLines(oldText)
  const newLines = splitLines(newText)
  const lcs = longestCommonSubsequence(oldLines, newLines)
  return {
    deletions: oldLines.length - lcs,
    additions: newLines.length - lcs,
  }
}

const longestCommonSubsequence = (
  a: ReadonlyArray<string>,
  b: ReadonlyArray<string>,
): number => {
  const n = a.length
  const m = b.length
  if (n === 0 || m === 0) return 0
  let prev = new Array<number>(m + 1).fill(0)
  let curr = new Array<number>(m + 1).fill(0)
  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      curr[j] = a[i - 1] === b[j - 1]
        ? (prev[j - 1] ?? 0) + 1
        : Math.max(prev[j] ?? 0, curr[j - 1] ?? 0)
    }
    const tmp = prev
    prev = curr
    curr = tmp
    curr.fill(0)
  }
  return prev[m] ?? 0
}

export const undoPlan = (change: FileChange, diskNow?: string): UndoPlan => {
  switch (change.kind) {
    case "modify": {
      if (!change.wholeFile) return { _tag: "disabled", reason: REGION_ONLY_REASON }
      if (change.oldSnapshot === undefined) return { _tag: "disabled", reason: "missing snapshot" }
      return { _tag: "replace", path: change.path, text: change.oldSnapshot }
    }
    case "delete": {
      if (change.oldSnapshot === undefined) return { _tag: "disabled", reason: "missing snapshot" }
      if (diskNow !== undefined && diskNow !== change.oldSnapshot) {
        return { _tag: "disabled", reason: "path exists with different content" }
      }
      return { _tag: "create", path: change.path, text: change.oldSnapshot }
    }
    case "add": {
      const dirty = diskNow !== undefined
        && change.newSnapshot !== undefined
        && diskNow !== change.newSnapshot
      return { _tag: "delete", path: change.path, confirmIfDirty: dirty }
    }
    case "move": {
      if (change.fromPath === undefined) return { _tag: "disabled", reason: "move target unknown" }
      if (change.oldSnapshot === undefined) return { _tag: "disabled", reason: "missing snapshot" }
      return {
        _tag: "moveReverse",
        fromPath: change.fromPath,
        toPath: change.path,
        oldText: change.oldSnapshot,
      }
    }
  }
}

export const applyUndoToSnapshots = (change: FileChange): string | undefined => {
  const plan = undoPlan(change)
  switch (plan._tag) {
    case "replace":
    case "create":
    case "moveReverse":
      return change.oldSnapshot
    case "delete":
      return undefined
    case "disabled":
      return change.oldSnapshot
  }
}

export type UndoMutation =
  | { readonly _tag: "replace"; readonly path: string; readonly text: string }
  | { readonly _tag: "create"; readonly path: string; readonly text: string }
  | { readonly _tag: "delete"; readonly path: string }

export type UndoResolution =
  | { readonly _tag: "apply"; readonly mutations: ReadonlyArray<UndoMutation> }
  | { readonly _tag: "disabled"; readonly reason: string }
  | { readonly _tag: "cancelled" }

export const mutationsFromUndoPlan = (
  plan: Exclude<UndoPlan, { readonly _tag: "disabled" }>,
): ReadonlyArray<UndoMutation> => {
  switch (plan._tag) {
    case "replace":
      return [{ _tag: "replace", path: plan.path, text: plan.text }]
    case "create":
      return [{ _tag: "create", path: plan.path, text: plan.text }]
    case "delete":
      return [{ _tag: "delete", path: plan.path }]
    case "moveReverse":
      return [
        { _tag: "create", path: plan.fromPath, text: plan.oldText },
        { _tag: "delete", path: plan.toPath },
      ]
  }
}

export const resolveUndo = async (
  change: FileChange,
  diskNow: string | undefined,
  confirmDirty: () => Promise<boolean>,
): Promise<UndoResolution> => {
  const plan = undoPlan(change, diskNow)
  if (plan._tag === "disabled") return plan
  if (plan._tag === "delete" && plan.confirmIfDirty) {
    if (!(await confirmDirty())) return { _tag: "cancelled" }
  }
  return { _tag: "apply", mutations: mutationsFromUndoPlan(plan) }
}
