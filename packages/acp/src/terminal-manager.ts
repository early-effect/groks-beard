import { RequestError } from "@agentclientprotocol/sdk"
import {
  commandHeadName,
  type ShellDialect,
  truncateKeepingUtf8Tail,
  utf8ByteLength,
} from "@groks-beard/core"
import { type ChildProcess, spawn } from "node:child_process"

export const TERM_GRACE_MS = 1000
export const KILL_GRACE_MS = 500

export type TerminalCreateParams = {
  readonly sessionId: string
  readonly command: string
  readonly args?: ReadonlyArray<string>
  readonly env?: ReadonlyArray<{ readonly name: string; readonly value: string }>
  readonly cwd?: string | null
  readonly outputByteLimit?: number | null
}

export type TerminalExitStatus = {
  readonly exitCode: number | null
  readonly signal: string | null
}

export type TerminalOutputResult = {
  readonly output: string
  readonly truncated: boolean
  readonly exitStatus?: TerminalExitStatus
}

export type TerminalManager = {
  readonly shellDialect: ShellDialect
  create: (params: TerminalCreateParams) => { terminalId: string }
  output: (terminalId: string) => TerminalOutputResult
  waitForExit: (terminalId: string) => Promise<TerminalExitStatus>
  kill: (terminalId: string) => void
  release: (terminalId: string) => void
  dispose: () => void
}

export type SpawnFn = (
  command: string,
  args: ReadonlyArray<string>,
  options: {
    cwd?: string
    env?: NodeJS.ProcessEnv
    stdio?: Array<"ignore" | "pipe"> | "ignore"
    windowsHide?: boolean
    shell?: boolean
    detached?: boolean
  },
) => ChildProcess

type TerminalRecord = {
  output: string
  truncated: boolean
  child?: ChildProcess
  exit?: TerminalExitStatus
  waiters: Array<(exit: TerminalExitStatus) => void>
  released: boolean
  tearingDown: boolean
  limit?: number
  termTimer?: ReturnType<typeof setTimeout>
  killTimer?: ReturnType<typeof setTimeout>
}

export const resolveAgentShell = (
  env: Record<string, string | undefined>,
  win: boolean,
  exists: (path: string) => boolean = () => false,
): string => {
  if (env.GROK_SHELL !== undefined && env.GROK_SHELL !== "") return env.GROK_SHELL
  if (!win) return env.SHELL !== undefined && env.SHELL !== "" ? env.SHELL : "/bin/sh"
  if (exists("pwsh.exe") || exists("pwsh")) return "pwsh.exe"
  return "powershell.exe"
}

export const shellDialectFor = (shell: string, win: boolean): ShellDialect => {
  const name = commandHeadName(shell)
  if (name === "cmd") return "cmd"
  if (name === "powershell" || name === "pwsh") return "powershell"
  if (win && name !== "bash" && name !== "sh" && name !== "zsh") return "powershell"
  return "posix"
}

const psQuote = (value: string): string => `'${value.replace(/'/g, "''")}'`

const cmdQuote = (value: string): string => {
  if (!/[\s"&<>|^]/.test(value)) return value
  return `"${value.replace(/"/g, '\\"')}"`
}

export const wrapSpawnInvocation = (input: {
  readonly command: string
  readonly args: ReadonlyArray<string>
  readonly shell: string
  readonly dialect: ShellDialect
  readonly win: boolean
}): { readonly command: string; readonly args: ReadonlyArray<string> } => {
  if (input.win) {
    if (input.dialect === "cmd") {
      const line = input.args.length === 0
        ? input.command
        : [cmdQuote(input.command), ...input.args.map(cmdQuote)].join(" ")
      return { command: input.shell, args: ["/d", "/s", "/c", line] }
    }
    const script = input.args.length === 0
      ? input.command
      : `& ${psQuote(input.command)} ${input.args.map(psQuote).join(" ")}`
    return {
      command: input.shell,
      args: ["-NoProfile", "-NonInteractive", "-Command", script],
    }
  }
  if (input.args.length === 0) {
    return { command: input.shell, args: ["-c", input.command] }
  }
  return { command: input.command, args: input.args }
}

export const killProcessTree = (
  child: ChildProcess,
  win: boolean,
  force: boolean,
  run: SpawnFn = spawn,
): void => {
  const pid = child.pid
  if (pid === undefined) {
    child.kill(force ? "SIGKILL" : "SIGTERM")
    return
  }
  if (win) {
    const args = force
      ? ["/PID", String(pid), "/T", "/F"]
      : ["/PID", String(pid), "/T"]
    run("taskkill", args, { stdio: "ignore", windowsHide: true, shell: false })
    return
  }
  try {
    process.kill(-pid, force ? "SIGKILL" : "SIGTERM")
  } catch {
    child.kill(force ? "SIGKILL" : "SIGTERM")
  }
}

const unknownTerminal = (terminalId: string): never => {
  throw RequestError.invalidParams({ terminalId }, `unknown terminal ${terminalId}`)
}

const toExitStatus = (code: number | null, signal: NodeJS.Signals | null): TerminalExitStatus => ({
  exitCode: code,
  signal,
})

export class MemoryTerminalManager implements TerminalManager {
  readonly shellDialect: ShellDialect
  readonly created: Array<TerminalCreateParams> = []
  private seq = 0
  private readonly terms = new Map<string, TerminalRecord>()
  private readonly autoExit: boolean

  constructor(options: { readonly shellDialect?: ShellDialect; readonly autoExit?: boolean } = {}) {
    this.shellDialect = options.shellDialect ?? "posix"
    this.autoExit = options.autoExit ?? true
  }

  create(params: TerminalCreateParams): { terminalId: string } {
    this.created.push(params)
    const terminalId = `mem-term-${++this.seq}`
    const record: TerminalRecord = {
      output: "",
      truncated: false,
      waiters: [],
      released: false,
      tearingDown: false,
    }
    this.terms.set(terminalId, record)
    if (this.autoExit) this.finish(terminalId, "", { exitCode: 0, signal: null })
    return { terminalId }
  }

  finish(terminalId: string, output: string, exit: TerminalExitStatus): void {
    const record = this.terms.get(terminalId)
    if (record === undefined || record.released) return
    record.output = output
    settle(record, exit)
  }

  output(terminalId: string): TerminalOutputResult {
    const record = this.require(terminalId)
    return outputOf(record)
  }

  waitForExit(terminalId: string): Promise<TerminalExitStatus> {
    const record = this.require(terminalId)
    if (record.exit !== undefined) return Promise.resolve(record.exit)
    return new Promise((resolve) => {
      record.waiters.push(resolve)
    })
  }

  kill(terminalId: string): void {
    const record = this.require(terminalId)
    this.finish(terminalId, record.output, { exitCode: null, signal: "SIGTERM" })
  }

  release(terminalId: string): void {
    const record = this.require(terminalId)
    if (record.exit === undefined) {
      this.finish(terminalId, record.output, { exitCode: null, signal: "SIGTERM" })
    }
    record.released = true
    this.terms.delete(terminalId)
  }

  dispose(): void {
    for (const id of [...this.terms.keys()]) {
      try {
        this.release(id)
      } catch {
        this.terms.delete(id)
      }
    }
  }

  private require(terminalId: string): TerminalRecord {
    const record = this.terms.get(terminalId)
    if (record === undefined || record.released) return unknownTerminal(terminalId)
    return record
  }
}

export class ChildProcessTerminalManager implements TerminalManager {
  readonly shellDialect: ShellDialect
  private seq = 0
  private readonly terms = new Map<string, TerminalRecord>()
  private readonly cwd: string
  private readonly env: NodeJS.ProcessEnv
  private readonly spawnFn: SpawnFn
  private readonly win: boolean
  private readonly shell: string
  private readonly termGraceMs: number
  private readonly killGraceMs: number

  constructor(options: {
    readonly cwd: string
    readonly env?: NodeJS.ProcessEnv
    readonly shell?: string
    readonly win?: boolean
    readonly spawn?: SpawnFn
    readonly termGraceMs?: number
    readonly killGraceMs?: number
  }) {
    this.cwd = options.cwd
    this.env = options.env ?? process.env
    this.win = options.win ?? process.platform === "win32"
    this.shell = options.shell ?? resolveAgentShell(
      this.env as Record<string, string | undefined>,
      this.win,
    )
    this.shellDialect = shellDialectFor(this.shell, this.win)
    this.spawnFn = options.spawn ?? spawn
    this.termGraceMs = options.termGraceMs ?? TERM_GRACE_MS
    this.killGraceMs = options.killGraceMs ?? KILL_GRACE_MS
  }

  create(params: TerminalCreateParams): { terminalId: string } {
    const terminalId = `beard-term-${++this.seq}`
    const record: TerminalRecord = {
      output: "",
      truncated: false,
      waiters: [],
      released: false,
      tearingDown: false,
      ...(params.outputByteLimit !== undefined && params.outputByteLimit !== null
        ? { limit: params.outputByteLimit }
        : {}),
    }
    this.terms.set(terminalId, record)
    const childEnv: NodeJS.ProcessEnv = { ...this.env, SHELL: this.shell }
    if (this.env.GROK_SHELL !== undefined) childEnv.GROK_SHELL = this.env.GROK_SHELL
    else childEnv.GROK_SHELL = this.shell
    for (const extra of params.env ?? []) childEnv[extra.name] = extra.value
    const cwd = params.cwd !== undefined && params.cwd !== null && params.cwd !== ""
      ? params.cwd
      : this.cwd
    const invocation = wrapSpawnInvocation({
      command: params.command,
      args: params.args ?? [],
      shell: this.shell,
      dialect: this.shellDialect,
      win: this.win,
    })
    try {
      const child = this.spawnFn(invocation.command, [...invocation.args], {
        cwd,
        env: childEnv,
        stdio: ["ignore", "pipe", "pipe"],
        windowsHide: true,
        shell: false,
        detached: true,
      })
      record.child = child
      attachOutput(record, child)
      child.on("error", (error) => {
        appendOutput(record, error instanceof Error ? error.message : String(error))
        settle(record, { exitCode: 127, signal: null })
      })
      child.on("close", (code, signal) => {
        settle(record, toExitStatus(code, signal))
        if (record.released) this.terms.delete(terminalId)
      })
    } catch (error) {
      appendOutput(record, error instanceof Error ? error.message : String(error))
      settle(record, { exitCode: 127, signal: null })
    }
    return { terminalId }
  }

  output(terminalId: string): TerminalOutputResult {
    return outputOf(this.require(terminalId))
  }

  waitForExit(terminalId: string): Promise<TerminalExitStatus> {
    const record = this.require(terminalId)
    if (record.exit !== undefined) return Promise.resolve(record.exit)
    return new Promise((resolve) => {
      record.waiters.push(resolve)
    })
  }

  kill(terminalId: string): void {
    this.beginTeardown(this.require(terminalId))
  }

  release(terminalId: string): void {
    const record = this.require(terminalId)
    this.beginTeardown(record)
    record.released = true
    if (record.exit !== undefined) this.terms.delete(terminalId)
  }

  dispose(): void {
    for (const record of [...this.terms.values()]) {
      try {
        this.beginTeardown(record)
      } catch {
        try {
          if (record.child !== undefined) {
            killProcessTree(record.child, this.win, true, this.spawnFn)
          }
        } catch {
          /* best-effort */
        }
      }
      record.released = true
    }
  }

  private beginTeardown(record: TerminalRecord): void {
    if (record.exit !== undefined || record.tearingDown) return
    record.tearingDown = true
    const child = record.child
    if (child === undefined) {
      settle(record, { exitCode: null, signal: "SIGTERM" })
      return
    }
    killProcessTree(child, this.win, false, this.spawnFn)
    record.termTimer = setTimeout(() => {
      if (record.exit !== undefined) return
      killProcessTree(child, this.win, true, this.spawnFn)
      record.killTimer = setTimeout(() => {
        if (record.exit !== undefined) return
        settle(record, { exitCode: null, signal: "SIGKILL" })
      }, this.killGraceMs)
    }, this.termGraceMs)
  }

  private require(terminalId: string): TerminalRecord {
    const record = this.terms.get(terminalId)
    if (record === undefined || record.released) return unknownTerminal(terminalId)
    return record
  }
}

const attachOutput = (record: TerminalRecord, child: ChildProcess): void => {
  attachStream(record, child.stdout ?? undefined)
  attachStream(record, child.stderr ?? undefined)
}

const attachStream = (
  record: TerminalRecord,
  stream: NodeJS.ReadableStream | undefined,
): void => {
  if (stream === undefined) return
  const decoder = new TextDecoder()
  stream.on("data", (chunk: Buffer | string) => {
    const bytes = typeof chunk === "string" ? Buffer.from(chunk) : chunk
    appendOutput(record, decoder.decode(bytes, { stream: true }))
  })
  stream.on("end", () => {
    appendOutput(record, decoder.decode())
  })
}

const appendOutput = (record: TerminalRecord, text: string): void => {
  if (text === "") return
  record.output += text
  if (record.limit !== undefined && utf8ByteLength(record.output) > record.limit) {
    record.output = truncateKeepingUtf8Tail(record.output, record.limit)
    record.truncated = true
  }
}

const clearTeardownTimers = (record: TerminalRecord): void => {
  if (record.termTimer !== undefined) clearTimeout(record.termTimer)
  if (record.killTimer !== undefined) clearTimeout(record.killTimer)
  delete record.termTimer
  delete record.killTimer
}

const settle = (record: TerminalRecord, exit: TerminalExitStatus): void => {
  if (record.exit !== undefined) return
  record.exit = exit
  clearTeardownTimers(record)
  for (const waiter of record.waiters.splice(0)) waiter(exit)
}

const outputOf = (record: TerminalRecord): TerminalOutputResult => ({
  output: record.output,
  truncated: record.truncated,
  ...(record.exit !== undefined ? { exitStatus: record.exit } : {}),
})
