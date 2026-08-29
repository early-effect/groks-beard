import { expect, it } from "@effect/vitest"
import { isReadOnlyCommand, shouldBlockTerminal } from "../src/plan-terminal.ts"
import { truncateKeepingUtf8Tail } from "../src/utf8.ts"

it("allows read-only inspection commands", () => {
  expect(isReadOnlyCommand("ls")).toBe(true)
  expect(isReadOnlyCommand("ls", ["-la"])).toBe(true)
  expect(isReadOnlyCommand("cat", ["README.md"])).toBe(true)
  expect(isReadOnlyCommand("git", ["status"])).toBe(true)
  expect(isReadOnlyCommand("git status")).toBe(true)
  expect(isReadOnlyCommand("cd repo && git status")).toBe(true)
  expect(isReadOnlyCommand("Get-ChildItem", [], "powershell")).toBe(true)
})

it("rejects mutating shells", () => {
  expect(isReadOnlyCommand("rm", ["-rf", "/tmp/x"])).toBe(false)
  expect(isReadOnlyCommand("rm -rf /tmp/x")).toBe(false)
  expect(isReadOnlyCommand("npm", ["install"])).toBe(false)
  expect(isReadOnlyCommand("git", ["commit", "-m", "x"])).toBe(false)
  expect(isReadOnlyCommand("echo hi > file")).toBe(false)
  expect(isReadOnlyCommand("bash", ["-c", "rm -rf /tmp/x"])).toBe(false)
  expect(isReadOnlyCommand("find", [".", "-delete"])).toBe(false)
})

it("fails closed on awk and sed program bodies", () => {
  expect(isReadOnlyCommand("awk", [String.raw`BEGIN{system("rm -rf /tmp/x")}`])).toBe(false)
  expect(isReadOnlyCommand(String.raw`awk 'BEGIN{system("rm -rf /tmp/x")}'`)).toBe(false)
  expect(isReadOnlyCommand("bash", ["-c", String.raw`awk 'BEGIN{system("rm")}'`])).toBe(false)
  expect(isReadOnlyCommand("sed", ["e rm -rf /tmp/x"])).toBe(false)
  expect(isReadOnlyCommand("sed", ["s/a/b/"])).toBe(false)
})

it("allows only listing git branch flags", () => {
  expect(isReadOnlyCommand("git", ["branch"])).toBe(true)
  expect(isReadOnlyCommand("git", ["branch", "-a"])).toBe(true)
  expect(isReadOnlyCommand("git", ["branch", "--list"])).toBe(true)
  expect(isReadOnlyCommand("git", ["branch", "--set-upstream-to=origin/main"])).toBe(false)
  expect(isReadOnlyCommand("git", ["branch", "--unset-upstream"])).toBe(false)
})

it("treats bash -c scripts as the inner command", () => {
  expect(isReadOnlyCommand("bash", ["-c", "ls -la"])).toBe(true)
  expect(isReadOnlyCommand("bash", ["-c", "ls && git status"])).toBe(true)
})

it("blocks mutating commands only while planActive", () => {
  expect(shouldBlockTerminal("rm", ["-rf", "/"], false)).toBe(false)
  expect(shouldBlockTerminal("rm", ["-rf", "/"], true)).toBe(true)
  expect(shouldBlockTerminal("ls", [], true)).toBe(false)
})

it("blocks mutating commands for any session while planActive", () => {
  expect(shouldBlockTerminal("rm", ["-rf", "a"], true)).toBe(true)
  expect(shouldBlockTerminal("ls", [], true)).toBe(false)
})

it("keeps the utf8 tail when truncating", () => {
  expect(truncateKeepingUtf8Tail("abcdef", 3)).toBe("def")
})
