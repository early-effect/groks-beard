import { execFileSync } from "node:child_process"
import { relative } from "node:path"

export const NO_BEARD_SNAPSHOT_NOTICE = "no Beard snapshot"

export type BeardSnapshotPair = {
  readonly original: string
  readonly proposed: string
}

export type PathDiffResolution =
  | {
    readonly ok: true
    readonly source: "beard" | "git" | "disk"
    readonly path: string
    readonly original: string
    readonly proposed: string
    readonly notice?: string
  }
  | { readonly ok: false; readonly reason: string }

export const resolvePathDiff = (input: {
  readonly path: string
  readonly beard?: BeardSnapshotPair
  readonly gitHead?: string
  readonly disk?: string
}): PathDiffResolution => {
  if (input.path === "") return { ok: false, reason: "path is required" }
  if (input.beard !== undefined) {
    return {
      ok: true,
      source: "beard",
      path: input.path,
      original: input.beard.original,
      proposed: input.beard.proposed,
    }
  }
  if (input.gitHead !== undefined) {
    return {
      ok: true,
      source: "git",
      path: input.path,
      original: input.gitHead,
      proposed: input.disk ?? input.gitHead,
    }
  }
  if (input.disk !== undefined) {
    return {
      ok: true,
      source: "disk",
      path: input.path,
      original: input.disk,
      proposed: input.disk,
      notice: NO_BEARD_SNAPSHOT_NOTICE,
    }
  }
  return { ok: false, reason: "file not found" }
}

export const gitHeadText = (workspace: string, absPath: string): string | undefined => {
  const rel = relative(workspace, absPath)
  if (rel === "" || rel.startsWith("..") || rel.startsWith("...")) return undefined
  try {
    return execFileSync("git", ["-C", workspace, "show", `HEAD:${rel.replaceAll("\\", "/")}`], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    })
  } catch {
    return undefined
  }
}
