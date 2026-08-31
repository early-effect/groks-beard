import { expect, it } from "@effect/vitest"
import { commitPaths, defaultCommitMessage } from "../src/git-commit.ts"

it("builds a commit message from turn titles", () => {
  expect(defaultCommitMessage([])).toBe("Grok changes")
  expect(defaultCommitMessage(["Untitled", ""])).toBe("Grok changes")
  expect(defaultCommitMessage(["Fix the locator"])).toBe("Fix the locator")
  expect(defaultCommitMessage(["One", "Two", "Two"])).toBe("One; Two")
})

it("stages unique paths then commits", async () => {
  const added: Array<ReadonlyArray<string>> = []
  const committed: Array<string> = []
  const count = await commitPaths(
    ["/tmp/a.ts", "/tmp/a.ts", "/tmp/b.ts"],
    "  Fix the locator  ",
    {
      add: async (paths) => {
        added.push(paths)
      },
      commit: async (message) => {
        committed.push(message)
      },
    },
  )
  expect(count).toBe(2)
  expect(added).toEqual([["/tmp/a.ts", "/tmp/b.ts"]])
  expect(committed).toEqual(["Fix the locator"])
})

it("refuses an empty path list or blank message", async () => {
  const ports = {
    add: async () => undefined,
    commit: async () => undefined,
  }
  await expect(commitPaths([], "msg", ports)).rejects.toThrow("No files to commit")
  await expect(commitPaths(["/tmp/a.ts"], "  ", ports)).rejects.toThrow("Commit message is empty")
})
