import { expect, it } from "@effect/vitest"
import { CliNotFound } from "@groks-beard/core"
import { Effect } from "effect"
import { locateGrokCli } from "../src/cli-locator.ts"

it("prefers groksBeard.cliPath when the file exists", () => {
  const path = Effect.runSync(locateGrokCli({
    cliPath: "/opt/grok",
    env: { HOME: "/Users/russ" },
    exists: (candidate) => candidate === "/opt/grok",
  }))
  expect(path).toBe("/opt/grok")
})

it("falls back to GROK_HOME/bin then PATH", () => {
  const path = Effect.runSync(locateGrokCli({
    env: {
      GROK_HOME: "/custom/grok-home",
      PATH: "/usr/bin:/hidden",
    },
    exists: (candidate) => candidate === "/hidden/grok",
  }))
  expect(path).toBe("/hidden/grok")
})

it("resolves Windows .cmd shims to grok.exe", () => {
  const path = Effect.runSync(locateGrokCli({
    env: { USERPROFILE: "C:/Users/russ", Path: "C:/tools" },
    exists: (candidate) => candidate === "C:/tools/grok.cmd" || candidate === "C:/tools/grok.exe",
    win: true,
  }))
  expect(path).toBe("C:/tools/grok.exe")
})

it("fails with CliNotFound listing searched paths", () => {
  const error = Effect.runSync(Effect.flip(locateGrokCli({
    env: { HOME: "/Users/russ" },
    exists: () => false,
  })))
  expect(error).toBeInstanceOf(CliNotFound)
  expect(error.searched.some((path) => path.endsWith("/.grok/bin/grok"))).toBe(true)
})
