import { expect, it } from "@effect/vitest"
import {
  compareSessionActivity,
  encodeCwd,
  encodedCwdExceedsLimit,
  grokHome,
  indexSessions
} from "../src/session-index.ts"

it("encodes cwd the way Grok groups sessions", () => {
  expect(encodeCwd("/Users/russ/projects/fun/groks-beard")).toBe(
    "%2FUsers%2Fruss%2Fprojects%2Ffun%2Fgroks-beard"
  )
})

it("flags encoded names over 255 bytes", () => {
  expect(encodedCwdExceedsLimit("short")).toBe(false)
  expect(encodedCwdExceedsLimit("x".repeat(300))).toBe(true)
})

it("prefers GROK_HOME over HOME", () => {
  expect(grokHome({ GROK_HOME: "/custom", HOME: "/Users/russ" })).toBe("/custom")
  expect(grokHome({ HOME: "/Users/russ" })).toBe("/Users/russ/.grok")
  expect(grokHome({ USERPROFILE: "C:\\\\Users\\\\russ" })).toBe("C:\\\\Users\\\\russ/.grok")
})

it("orders sessions by updates.jsonl mtime, not id", () => {
  const ordered = indexSessions([
    { id: "old-uuid", updatesMtimeMs: 1 },
    { id: "new-uuid", updatesMtimeMs: 9 },
    { id: "no-updates", summaryMtimeMs: 5 }
  ])
  expect(ordered.map((row) => row.id)).toEqual(["new-uuid", "no-updates", "old-uuid"])
  expect(compareSessionActivity(ordered[0]!, ordered[1]!)).toBeLessThan(0)
})
