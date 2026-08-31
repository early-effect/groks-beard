import { CliNotFound, grokHome } from "@groks-beard/core"
import { Effect } from "effect"

export type LocateGrokInput = {
  readonly cliPath?: string
  readonly env: Record<string, string | undefined>
  readonly exists: (path: string) => boolean
  readonly win?: boolean
}

const join = (dir: string, name: string): string => `${dir.replace(/[\\/]+$/, "")}/${name}`

export const grokBinaryName = (win: boolean): string => (win ? "grok.exe" : "grok")

export const resolveSpawnTarget = (
  candidate: string,
  exists: (path: string) => boolean,
): string => {
  if (/\.(cmd|bat)$/i.test(candidate)) {
    const exe = candidate.replace(/\.(cmd|bat)$/i, ".exe")
    if (exists(exe)) return exe
  }
  return candidate
}

export const candidateGrokPaths = (input: LocateGrokInput): ReadonlyArray<string> => {
  const win = input.win ?? false
  const bin = grokBinaryName(win)
  const out: Array<string> = []
  if (input.cliPath !== undefined && input.cliPath !== "") out.push(input.cliPath)
  out.push(join(join(grokHome(input.env), "bin"), bin))
  const pathEnv = input.env.PATH ?? input.env.Path ?? ""
  const sep = win ? ";" : ":"
  for (const dir of pathEnv.split(sep)) {
    if (dir.trim() === "") continue
    out.push(join(dir, bin))
    if (win) out.push(join(dir, "grok.cmd"))
  }
  return out
}

export const locateGrokCli = (input: LocateGrokInput): Effect.Effect<string, CliNotFound> => {
  const searched = candidateGrokPaths(input)
  const hit = searched.find((path) => input.exists(path))
  if (hit === undefined) return Effect.fail(new CliNotFound({ searched: [...searched] }))
  return Effect.succeed(resolveSpawnTarget(hit, input.exists))
}
