import { RequestError } from "@agentclientprotocol/sdk"
import { expect, it } from "@effect/vitest"
import { tmpdir } from "node:os"
import {
  ChildProcessTerminalManager,
  MemoryTerminalManager,
  resolveAgentShell,
  shellDialectFor,
} from "../src/terminal-manager.ts"

it("prefers GROK_SHELL then PowerShell on Windows", () => {
  expect(resolveAgentShell({ GROK_SHELL: "/custom/sh" }, false)).toBe("/custom/sh")
  expect(resolveAgentShell({ SHELL: "/bin/zsh" }, false)).toBe("/bin/zsh")
  expect(resolveAgentShell({}, false)).toBe("/bin/sh")
  expect(resolveAgentShell({}, true)).toBe("powershell.exe")
  expect(resolveAgentShell({}, true, (path) => path === "pwsh.exe")).toBe("pwsh.exe")
  expect(shellDialectFor("powershell.exe", true)).toBe("powershell")
  expect(shellDialectFor("/bin/zsh", false)).toBe("posix")
  expect(shellDialectFor("cmd.exe", true)).toBe("cmd")
})

it("memory manager records create and auto-exits", async () => {
  const terminal = new MemoryTerminalManager()
  const created = terminal.create({ sessionId: "s", command: "ls" })
  expect(created.terminalId).toMatch(/^mem-term-/)
  expect(terminal.created[0]?.command).toBe("ls")
  const output = terminal.output(created.terminalId)
  expect(output.truncated).toBe(false)
  expect(output.exitStatus?.exitCode).toBe(0)
  const exit = await terminal.waitForExit(created.terminalId)
  expect(exit.exitCode).toBe(0)
  terminal.release(created.terminalId)
  expect(() => terminal.output(created.terminalId)).toThrow(RequestError)
})

it("child process manager captures stdout without a PTY", async () => {
  const terminal = new ChildProcessTerminalManager({ cwd: tmpdir() })
  const created = terminal.create({
    sessionId: "s",
    command: process.execPath,
    args: ["-e", "process.stdout.write('hello-beard')"],
  })
  const exit = await terminal.waitForExit(created.terminalId)
  expect(exit.exitCode).toBe(0)
  expect(terminal.output(created.terminalId).output).toContain("hello-beard")
  terminal.release(created.terminalId)
})

it("kills a running process and truncates from the start", async () => {
  const terminal = new ChildProcessTerminalManager({ cwd: tmpdir() })
  const hanging = terminal.create({
    sessionId: "s",
    command: process.execPath,
    args: ["-e", "setInterval(() => {}, 1000)"],
  })
  terminal.kill(hanging.terminalId)
  const exit = await terminal.waitForExit(hanging.terminalId)
  expect(exit.exitCode === null || exit.exitCode !== 0 || exit.signal !== null).toBe(true)
  terminal.release(hanging.terminalId)

  const limited = terminal.create({
    sessionId: "s",
    command: process.execPath,
    args: ["-e", "process.stdout.write('abcdef')"],
    outputByteLimit: 3,
  })
  await terminal.waitForExit(limited.terminalId)
  const output = terminal.output(limited.terminalId)
  expect(output.output).toBe("def")
  expect(output.truncated).toBe(true)
  terminal.dispose()
})
