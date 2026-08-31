import { expect, it } from "@effect/vitest"
import { mentionFilePattern, rankMentionFiles } from "../src/file-search.ts"

it("does not search the whole workspace for a blank @ query", () => {
  expect(mentionFilePattern("")).toBeUndefined()
  expect(mentionFilePattern("   ")).toBeUndefined()
  expect(mentionFilePattern("Main")).toBe("**/*Main*")
  expect(mentionFilePattern("foo*bar")).toBe("**/*foobar*")
})

it("ranks filename prefix matches ahead of path noise", () => {
  const ranked = rankMentionFiles([
    { path: "docs/security.md" },
    { path: "SECURITY.md" },
    { path: "project/src/Main.scala" },
  ], "sec")
  expect(ranked.map((row) => row.path)).toEqual([
    "SECURITY.md",
    "docs/security.md",
    "project/src/Main.scala",
  ])
})
