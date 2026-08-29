import {
  decodeSessionSummary,
  indexSessions,
  type SessionActivityStat,
  type SessionSummary
} from "@groks-beard/core"

export type DirEntry = {
  readonly name: string
  readonly isDirectory: boolean
}

export type SessionFs = {
  readonly list: (dir: string) => ReadonlyArray<DirEntry>
  readonly mtimeMs: (path: string) => number | undefined
  readonly readText: (path: string) => string | undefined
}

const join = (dir: string, name: string): string =>
  `${dir.replace(/[\\/]+$/, "")}/${name}`

export const statsForSessionGroup = (
  fs: SessionFs,
  groupDir: string
): ReadonlyArray<SessionActivityStat> => {
  const stats: Array<SessionActivityStat> = []
  for (const entry of fs.list(groupDir)) {
    if (!entry.isDirectory) continue
    const dir = join(groupDir, entry.name)
    const updates = fs.mtimeMs(join(dir, "updates.jsonl"))
    const events = fs.mtimeMs(join(dir, "events.jsonl"))
    const summary = fs.mtimeMs(join(dir, "summary.json"))
    stats.push({
      id: entry.name,
      ...(updates !== undefined ? { updatesMtimeMs: updates } : {}),
      ...(events !== undefined ? { eventsMtimeMs: events } : {}),
      ...(summary !== undefined ? { summaryMtimeMs: summary } : {})
    })
  }
  return indexSessions(stats)
}

export const readSessionSummaries = (
  fs: SessionFs,
  groupDir: string,
  ids: ReadonlyArray<string>
): ReadonlyArray<{ readonly id: string; readonly summary: SessionSummary | undefined }> =>
  ids.map((id) => {
    const text = fs.readText(join(join(groupDir, id), "summary.json"))
    if (text === undefined) return { id, summary: undefined }
    try {
      return { id, summary: decodeSessionSummary(JSON.parse(text) as unknown) }
    } catch {
      return { id, summary: undefined }
    }
  })
