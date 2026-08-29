import { RequestError } from "@agentclientprotocol/sdk"
import { expect, it } from "@effect/vitest"
import { tmpdir } from "node:os"
import {
  ChildProcessTerminalManager,
  MemoryTerminalManager,
  resolveAgentShell,
  shellDialectFor,
  wrapSpawnInvocation,
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

it("wraps argv-less Unix and all Windows creates in the resolved shell", () => {
  expect(wrapSpawnInvocation({
    command: "ls",
    args: [],
    shell: "/bin/zsh",
    dialect: "posix",
    win: false,
  })).toEqual({ command: "/bin/zsh", args: ["-c", "ls"] })
  expect(wrapSpawnInvocation({
    command: process.execPath,
    args: ["-e", "0"],
    shell: "/bin/zsh",
    dialect: "posix",
    win: false,
  })).toEqual({ command: process.execPath, args: ["-e", "0"] })
  expect(wrapSpawnInvocation({
    command: "Get-ChildItem",
    args: [],
    shell: "powershell.exe",
    dialect: "powershell",
    win: true,
  })).toEqual({
    command: "powershell.exe",
    args: ["-NoProfile", "-NonInteractive", "-Command", "Get-ChildItem"],
  })
})

it("kills a running process and truncates from the start", async () => {
  const terminal = new ChildProcessTerminalManager({
    cwd: tmpdir(),
    termGraceMs: 40,
    killGraceMs: 40,
  })
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

it("SIGKILLs a child that ignores SIGTERM so wait_for_exit cannot hang", async () => {
  const terminal = new ChildProcessTerminalManager({
    cwd: tmpdir(),
    termGraceMs: 40,
    killGraceMs: 40,
  })
  const created = terminal.create({
    sessionId: "s",
    command: process.execPath,
    args: ["-e", "process.on('SIGTERM', () => {}); setInterval(() => {}, 1000)"],
  })
  const started = Date.now()
  terminal.kill(created.terminalId)
  const exit = await terminal.waitForExit(created.terminalId)
  expect(Date.now() - started).toBeLessThan(2000)
  expect(exit.signal === "SIGKILL" || exit.exitCode === null || exit.exitCode !== 0).toBe(true)
  terminal.release(created.terminalId)
})

it("kills a bash -c grandchild when tearing down the process group", async () => {
  const terminal = new ChildProcessTerminalManager({
    cwd: tmpdir(),
    termGraceMs: 40,
    killGraceMs: 40,
  })
  const created = terminal.create({
    sessionId: "s",
    command: "bash",
    args: ["-c", "sleep 30"],
  })
  const started = Date.now()
  terminal.kill(created.terminalId)
  await terminal.waitForExit(created.terminalId)
  expect(Date.now() - started).toBeLessThan(2000)
  terminal.release(created.terminalId)
})
