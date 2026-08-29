import { expect, it } from "@effect/vitest"
import { createHash } from "node:crypto"
import { socketAddress, workspaceSocketHash } from "../src/socket-address.ts"

const hashOf = (workspace: string, win = false): string =>
  createHash("sha256").update(win ? workspace.toLowerCase() : workspace).digest("hex").slice(0, 16)

it("uses XDG_RUNTIME_DIR and a 16-char workspace hash on unix", () => {
  const workspace = "/Users/russ/proj"
  const address = socketAddress({
    workspace,
    win: false,
    runtimeDir: "/run/user/501",
    realpath: (path) => path,
  })
  expect(address).toBe(`/run/user/501/groks-beard/${hashOf(workspace)}.sock`)
  expect(workspaceSocketHash(workspace, false, (path) => path)).toHaveLength(16)
})

it("falls back to tmpdir when XDG_RUNTIME_DIR is unset", () => {
  const workspace = "/tmp/ws"
  const address = socketAddress({
    workspace,
    win: false,
    tmpdir: "/var/tmp",
    realpath: (path) => path,
  })
  expect(address).toBe(`/var/tmp/groks-beard/${hashOf(workspace)}.sock`)
})

it("uses a named pipe on Windows and lowercases the workspace path", () => {
  const address = socketAddress({
    workspace: "C:\\Users\\Russ\\Proj",
    win: true,
    realpath: (path) => path,
  })
  expect(address).toBe(`\\\\.\\pipe\\groks-beard-${hashOf("C:\\Users\\Russ\\Proj", true)}`)
  expect(socketAddress({
    workspace: "c:\\users\\russ\\proj",
    win: true,
    realpath: (path) => path,
  })).toBe(address)
})
