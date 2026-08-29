import { createHash } from "node:crypto"
import { realpathSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

export const SOCKET_MODE = 0o600
export const SOCKET_DIR_MODE = 0o700

export type SocketAddressInput = {
  readonly workspace: string
  readonly win?: boolean
  readonly runtimeDir?: string
  readonly tmpdir?: string
  readonly env?: Record<string, string | undefined>
  readonly realpath?: (path: string) => string
}

export const normalizeWorkspacePath = (
  workspace: string,
  win: boolean,
  realpath: (path: string) => string = tryRealpath,
): string => {
  const resolved = realpath(workspace)
  return win ? resolved.toLowerCase() : resolved
}

export const workspaceSocketHash = (
  workspace: string,
  win: boolean,
  realpath?: (path: string) => string,
): string =>
  createHash("sha256").update(normalizeWorkspacePath(workspace, win, realpath ?? tryRealpath))
    .digest("hex")
    .slice(0, 16)

export const socketAddress = (input: SocketAddressInput): string => {
  const win = input.win ?? process.platform === "win32"
  const hash = workspaceSocketHash(input.workspace, win, input.realpath)
  if (win) return `\\\\.\\pipe\\groks-beard-${hash}`
  const env = input.env ?? process.env
  const runtime = input.runtimeDir
    ?? env.XDG_RUNTIME_DIR
    ?? input.tmpdir
    ?? tmpdir()
  return join(runtime, "groks-beard", `${hash}.sock`)
}

const tryRealpath = (path: string): string => {
  try {
    return realpathSync(path)
  } catch {
    return path
  }
}
