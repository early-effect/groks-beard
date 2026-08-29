import { NodeNotFound } from "@groks-beard/core"
import { Effect } from "effect"
import { resolveSpawnTarget } from "./cli-locator.js"

export type LocateNodeInput = {
  readonly nodePath?: string
  readonly env: Record<string, string | undefined>
  readonly exists: (path: string) => boolean
  readonly win?: boolean
}

const join = (dir: string, name: string): string => `${dir.replace(/[\\/]+$/, "")}/${name}`

export const nodeBinaryName = (win: boolean): string => (win ? "node.exe" : "node")

export const isAbsoluteNodePath = (path: string, win: boolean): boolean => {
  if (path === "") return false
  if (win) return /^[a-zA-Z]:[\\/]/.test(path) || path.startsWith("\\\\")
  return path.startsWith("/")
}

export const candidateNodePaths = (input: LocateNodeInput): ReadonlyArray<string> => {
  const win = input.win ?? false
  const bin = nodeBinaryName(win)
  const out: Array<string> = []
  if (input.nodePath !== undefined && isAbsoluteNodePath(input.nodePath, win)) {
    out.push(input.nodePath)
  }
  const pathEnv = input.env.PATH ?? input.env.Path ?? ""
  const sep = win ? ";" : ":"
  for (const dir of pathEnv.split(sep)) {
    if (dir.trim() === "") continue
    out.push(join(dir, bin))
    if (win) out.push(join(dir, "node.cmd"))
  }
  return out
}

export const locateNode = (input: LocateNodeInput): Effect.Effect<string, NodeNotFound> => {
  const searched = candidateNodePaths(input)
  const hit = searched.find((path) => input.exists(path))
  if (hit === undefined) return Effect.fail(new NodeNotFound({ searched: [...searched] }))
  return Effect.succeed(resolveSpawnTarget(hit, input.exists))
}
