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
  toolCallId: Schema.String
}) {}

export class ChangeSet extends Schema.Class<ChangeSet>("ChangeSet")({
  sessionId: Schema.String,
  turnId: Schema.String,
  title: Schema.String,
  files: Schema.Array(FileChange),
  createdAt: Schema.Number
}) {}

export const SNAPSHOT_FILE_CAP = 32
export const SNAPSHOT_BYTE_CAP = 16 * 1024 * 1024

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
    files: set.files.filter((file) => file.path !== path)
  })

export const keepAll = (set: ChangeSet): ChangeSet => new ChangeSet({ ...set, files: [] })

const splitLines = (text: string): ReadonlyArray<string> => text.split(/\r?\n/)

export const lineDiffStats = (
  oldText: string,
  newText: string
): { readonly additions: number; readonly deletions: number } => {
  const oldLines = splitLines(oldText)
  const newLines = splitLines(newText)
  const lcs = longestCommonSubsequence(oldLines, newLines)
  return {
    deletions: oldLines.length - lcs,
    additions: newLines.length - lcs
  }
}

const longestCommonSubsequence = (
  a: ReadonlyArray<string>,
  b: ReadonlyArray<string>
): number => {
  const n = a.length
  const m = b.length
  if (n === 0 || m === 0) return 0
  let prev = new Array<number>(m + 1).fill(0)
  let curr = new Array<number>(m + 1).fill(0)
  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      curr[j] = a[i - 1] === b[j - 1] ? (prev[j - 1] ?? 0) + 1 : Math.max(prev[j] ?? 0, curr[j - 1] ?? 0)
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
      const dirty = diskNow !== undefined &&
        change.newSnapshot !== undefined &&
        diskNow !== change.newSnapshot
      return { _tag: "delete", path: change.path, confirmIfDirty: dirty }
    }
    case "move": {
      if (change.fromPath === undefined) return { _tag: "disabled", reason: "move target unknown" }
      if (change.oldSnapshot === undefined) return { _tag: "disabled", reason: "missing snapshot" }
      return {
        _tag: "moveReverse",
        fromPath: change.fromPath,
        toPath: change.path,
        oldText: change.oldSnapshot
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
