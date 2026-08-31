import { changeSetLineStats, type ChangeSetRecord, formatLineStats } from "@groks-beard/core"
import { relative } from "node:path"

export type ReviewFileView = {
  readonly path: string
  readonly name: string
  readonly kind: string
  readonly additions: number
  readonly deletions: number
  readonly canUndo: boolean
}

export type ReviewTurnView = {
  readonly sessionId: string
  readonly turnId: string
  readonly title: string
  readonly additions: number
  readonly deletions: number
  readonly canUndo: boolean
  readonly files: ReadonlyArray<ReviewFileView>
}

export const displayChangePath = (absPath: string, workspaceRoot?: string): string => {
  if (workspaceRoot === undefined || workspaceRoot === "") return absPath
  const rel = relative(workspaceRoot, absPath)
  if (rel === "" || rel.startsWith("..")) return absPath
  return rel
}

export const reviewTurnsFromSets = (
  sets: ReadonlyArray<ChangeSetRecord>,
  workspaceRoot?: string,
): ReadonlyArray<ReviewTurnView> =>
  sets.map((set) => {
    const stats = changeSetLineStats(set.files)
    return {
      sessionId: set.sessionId,
      turnId: set.turnId,
      title: set.title,
      additions: stats.additions,
      deletions: stats.deletions,
      canUndo: set.files.some((file) => file.undoDisabledReason === undefined),
      files: set.files.map((file) => ({
        path: file.path,
        name: displayChangePath(file.path, workspaceRoot),
        kind: file.kind,
        additions: file.additions,
        deletions: file.deletions,
        canUndo: file.undoDisabledReason === undefined,
      })),
    }
  })

export const reviewStatePayload = (
  sets: ReadonlyArray<ChangeSetRecord>,
  workspaceRoot?: string,
): {
  readonly turns: ReadonlyArray<ReviewTurnView>
  readonly canUndoEvery: boolean
  readonly status: string
} => {
  const turns = reviewTurnsFromSets(sets, workspaceRoot)
  return {
    turns,
    canUndoEvery: turns.some((turn) => turn.canUndo),
    status: formatLineStats(
      turns.reduce((n, turn) => n + turn.additions, 0),
      turns.reduce((n, turn) => n + turn.deletions, 0),
    ),
  }
}
