import { expect, it } from "@effect/vitest"
import { NodeNotFound } from "@groks-beard/core"
import { Effect } from "effect"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { locateNode } from "../src/node-locator.ts"

it("prefers groksBeard.nodePath when the path is absolute and exists", () => {
  const path = Effect.runSync(locateNode({
    nodePath: "/opt/node",
    env: { PATH: "/usr/bin" },
    exists: (candidate) => candidate === "/opt/node",
  }))
  expect(path).toBe("/opt/node")
})

it("ignores a relative groksBeard.nodePath and searches PATH", () => {
  const path = Effect.runSync(locateNode({
    nodePath: "node",
    env: { PATH: "/usr/bin:/opt/bin" },
    exists: (candidate) => candidate === "/opt/bin/node",
  }))
  expect(path).toBe("/opt/bin/node")
})

it("resolves Windows .cmd shims to node.exe", () => {
  const path = Effect.runSync(locateNode({
    env: { Path: "C:/tools" },
    exists: (candidate) => candidate === "C:/tools/node.cmd" || candidate === "C:/tools/node.exe",
    win: true,
  }))
  expect(path).toBe("C:/tools/node.exe")
})

it("fails with NodeNotFound and never uses process.execPath", () => {
  const execPath = "/Applications/Cursor.app/Contents/MacOS/Cursor"
  const error = Effect.runSync(Effect.flip(locateNode({
    env: { PATH: "/Applications/Cursor.app/Contents/MacOS" },
    exists: (candidate) => candidate === execPath,
  })))
  expect(error).toBeInstanceOf(NodeNotFound)
  expect(error.searched.includes(execPath)).toBe(false)
  expect(error.searched.includes("/Applications/Cursor.app/Contents/MacOS/node")).toBe(true)
})

it("source never mentions process.execPath", () => {
  const src = readFileSync(
    fileURLToPath(new URL("../src/node-locator.ts", import.meta.url)),
    "utf8",
  )
  expect(src).not.toMatch(/execPath/)
})
