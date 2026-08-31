export type LiveStatus = "idle" | "working" | "needs-you"

export type Dot = "gray" | "blue" | "yellow" | "green" | "red"

export type PoolMember = {
  readonly sessionId: string
  readonly focused: boolean
  readonly liveStatus: LiveStatus
  readonly lastTouchedAt: number
  readonly unread: boolean
  readonly unreadError: boolean
}

export const IDLE_TTL_MS = 60 * 60 * 1000
export const LRU_CAP = 8

export const computeDot = (member: {
  readonly liveStatus: LiveStatus
  readonly unread: boolean
  readonly unreadError: boolean
}): Dot => {
  if (member.liveStatus === "working") return "blue"
  if (member.liveStatus === "needs-you") return "yellow"
  if (member.unreadError) return "red"
  if (member.unread) return "green"
  return "gray"
}

const isProtected = (member: PoolMember): boolean =>
  member.focused || member.liveStatus === "working" || member.liveStatus === "needs-you"

export const selectReapable = (
  members: ReadonlyArray<PoolMember>,
  now: number,
  options: { readonly idleTtlMs?: number; readonly lruCap?: number } = {},
): ReadonlyArray<string> => {
  const idleTtlMs = options.idleTtlMs ?? IDLE_TTL_MS
  const lruCap = options.lruCap ?? LRU_CAP
  const eligible = members.filter((member) => !isProtected(member))
  const idle = eligible.filter((member) => now - member.lastTouchedAt >= idleTtlMs)
  const idleIds = new Set(idle.map((member) => member.sessionId))
  const remaining = eligible
    .filter((member) => !idleIds.has(member.sessionId))
    .sort((a, b) => a.lastTouchedAt - b.lastTouchedAt)
  const overCap = remaining.length > lruCap ? remaining.slice(0, remaining.length - lruCap) : []
  return [...idle, ...overCap].map((member) => member.sessionId)
}
