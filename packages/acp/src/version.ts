export type GrokVersion = {
  readonly major: number
  readonly minor: number
  readonly patch: number
  readonly git?: string
  readonly channel?: string
  readonly raw: string
}

export type VersionCache = {
  readonly version: GrokVersion
  readonly mtimeMs: number
  readonly size: number
}

export type BinaryStat = {
  readonly mtimeMs: number
  readonly size: number
}

export type ResolvedVersion = {
  readonly version: GrokVersion | undefined
  readonly verified: boolean
}

const BANNER =
  /^grok\s+(\d+)\.(\d+)\.(\d+)(?:\s+\(([0-9a-f]+)\))?(?:\s+\[([^\]]+)\])?/i

export const parseGrokVersion = (stdout: string): GrokVersion | undefined => {
  const line = stdout.trim().split(/\r?\n/).find((row) => row.trim().length > 0) ?? ""
  const match = BANNER.exec(line.trim())
  if (match === null) return undefined
  const major = Number(match[1])
  const minor = Number(match[2])
  const patch = Number(match[3])
  const git = match[4]
  const channel = match[5]
  return {
    major,
    minor,
    patch,
    ...(git !== undefined ? { git } : {}),
    ...(channel !== undefined ? { channel } : {}),
    raw: line.trim()
  }
}

export const compareGrokVersion = (a: GrokVersion, b: Pick<GrokVersion, "major" | "minor" | "patch">): number => {
  if (a.major !== b.major) return a.major - b.major
  if (a.minor !== b.minor) return a.minor - b.minor
  return a.patch - b.patch
}

export const isAtLeast = (
  version: GrokVersion,
  minimum: Pick<GrokVersion, "major" | "minor" | "patch">
): boolean => compareGrokVersion(version, minimum) >= 0

export const resolveGrokVersion = (
  liveStdout: string,
  cache: VersionCache | undefined,
  stat: BinaryStat | undefined
): ResolvedVersion => {
  const live = parseGrokVersion(liveStdout)
  if (live !== undefined) return { version: live, verified: true }
  if (
    cache !== undefined &&
    stat !== undefined &&
    cache.mtimeMs === stat.mtimeMs &&
    cache.size === stat.size
  ) {
    return { version: cache.version, verified: false }
  }
  return { version: undefined, verified: false }
}
