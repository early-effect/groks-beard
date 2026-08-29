import { utf8ByteLength } from "./utf8.js"

export const SESSION_PAGE_SIZE = 100
export const MAX_ENCODED_CWD_BYTES = 255

export const encodeCwd = (cwd: string): string => encodeURIComponent(cwd)

export const encodedCwdExceedsLimit = (encoded: string): boolean =>
  utf8ByteLength(encoded) > MAX_ENCODED_CWD_BYTES

export const grokHome = (env: Record<string, string | undefined>): string => {
  if (env.GROK_HOME !== undefined && env.GROK_HOME !== "") return env.GROK_HOME
  const home = env.HOME ?? env.USERPROFILE
  if (home === undefined || home === "") return ".grok"
  return `${home.replace(/[\\/]+$/, "")}/.grok`
}

export type SessionActivityStat = {
  readonly id: string
  readonly updatesMtimeMs?: number
  readonly eventsMtimeMs?: number
  readonly summaryMtimeMs?: number
}

export const statSessionActivity = (stat: SessionActivityStat): number =>
  stat.updatesMtimeMs ?? stat.eventsMtimeMs ?? stat.summaryMtimeMs ?? 0

export const compareSessionActivity = (a: SessionActivityStat, b: SessionActivityStat): number =>
  statSessionActivity(b) - statSessionActivity(a)

export const indexSessions = (
  stats: ReadonlyArray<SessionActivityStat>
): ReadonlyArray<SessionActivityStat> => [...stats].sort(compareSessionActivity)

export const pageSessionIds = (
  ordered: ReadonlyArray<SessionActivityStat>,
  offset: number,
  limit: number = SESSION_PAGE_SIZE
): ReadonlyArray<string> => ordered.slice(offset, offset + limit).map((row) => row.id)
