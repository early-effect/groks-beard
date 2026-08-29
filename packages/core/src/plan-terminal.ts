export const PLAN_BLOCKED_CODE = -32010
export const PLAN_BLOCKED_TERMINAL_MSG =
  "Blocked by Plan mode: approve the plan before running commands that may change the workspace."

export type ShellDialect = "posix" | "powershell" | "cmd"

export const joinCommandLine = (
  command: string,
  args: ReadonlyArray<string> = [],
): string => (args.length === 0 ? command : [command, ...args].join(" "))

export const commandHeadName = (command: string): string => {
  const trimmed = command.trim()
  const base = trimmed.replace(/\\/g, "/").split("/").pop() ?? trimmed
  return base.replace(/\.(exe|cmd|bat)$/i, "").toLowerCase()
}

export const shouldBlockTerminal = (
  command: string,
  args: ReadonlyArray<string> = [],
  planActive: boolean,
  dialect: ShellDialect = "posix",
): boolean => planActive && !isReadOnlyCommand(command, args, dialect)

export const isReadOnlyCommand = (
  command: string,
  args: ReadonlyArray<string> = [],
  dialect: ShellDialect = "posix",
): boolean => {
  const trimmed = command.trim()
  if (trimmed === "") return false
  if (args.length > 0) return isReadOnlyArgv(trimmed, args, dialect)
  return isReadOnlyShellLine(trimmed, dialect)
}

const READONLY_HEADS = new Set([
  "ls",
  "dir",
  "pwd",
  "cd",
  "echo",
  "cat",
  "type",
  "head",
  "tail",
  "less",
  "more",
  "grep",
  "rg",
  "ag",
  "ack",
  "find",
  "fd",
  "tree",
  "wc",
  "stat",
  "file",
  "which",
  "where",
  "whereis",
  "basename",
  "dirname",
  "realpath",
  "readlink",
  "du",
  "df",
  "printenv",
  "date",
  "whoami",
  "hostname",
  "uname",
  "sort",
  "uniq",
  "cut",
  "cmp",
  "comm",
  "jq",
  "mdls",
  "sw_vers",
  "shasum",
  "md5",
  "cksum",
  "strings",
  "hexdump",
  "od",
  "nl",
  "paste",
  "join",
  "tr",
  "column",
  "ps",
  "id",
  "groups",
  "locale",
  "true",
  "false",
  "test",
  "git",
  "diff",
  "sed",
  "awk",
  "get-childitem",
  "gci",
  "get-content",
  "gc",
  "get-item",
  "gi",
  "get-itemproperty",
  "gp",
  "test-path",
  "resolve-path",
  "rvpa",
  "get-location",
  "gl",
  "select-object",
  "select",
  "format-table",
  "ft",
  "format-list",
  "fl",
  "sort-object",
  "measure-object",
  "measure",
  "select-string",
  "sls",
  "out-string",
  "get-command",
  "gcm",
  "get-help",
  "get-member",
  "gm",
  "write-output",
])

const GIT_READONLY = new Set([
  "status",
  "diff",
  "log",
  "show",
  "ls-files",
  "ls-tree",
  "rev-parse",
  "blame",
  "describe",
  "shortlog",
  "cat-file",
  "name-rev",
  "whatchanged",
  "show-ref",
  "for-each-ref",
  "merge-base",
  "check-ignore",
  "check-attr",
  "grep",
])

const PKG_READONLY = new Set(["ls", "list", "view", "info", "outdated", "why", "show", "audit"])

const SHELL_HEADS = new Set([
  "sh",
  "bash",
  "zsh",
  "fish",
  "dash",
  "ksh",
  "cmd",
  "powershell",
  "pwsh",
])

const INTERPRETERS = new Set(["node", "python", "python3", "deno", "ruby", "perl"])

const FIND_WRITE = new Set([
  "-delete",
  "-exec",
  "-execdir",
  "-ok",
  "-okdir",
  "-fprint",
  "-fprint0",
  "-fprintf",
  "-fls",
])

const FD_WRITE = new Set(["-x", "--exec", "--exec-batch", "-x", "-X"])

const isReadOnlyArgv = (
  command: string,
  args: ReadonlyArray<string>,
  dialect: ShellDialect,
): boolean => {
  const head = commandHeadName(command)
  if (SHELL_HEADS.has(head)) {
    const script = shellScriptArg(args)
    if (script === undefined) {
      return args.length > 0 && args.every((arg) => /^(-v|--version|--help|-h|-\?)$/i.test(arg))
    }
    const nested = head === "cmd" ? "cmd" : head === "powershell" || head === "pwsh"
      ? "powershell"
      : "posix"
    return isReadOnlyShellLine(script, nested)
  }
  return isReadOnlyStage([command, ...args], dialect)
}

const shellScriptArg = (args: ReadonlyArray<string>): string | undefined => {
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === undefined) continue
    if (arg === "-c" || arg === "/c" || arg === "-Command" || arg === "-command") {
      return args[i + 1]
    }
    if (arg.startsWith("-c") && arg.length > 2 && !arg.startsWith("-command")) {
      return arg.slice(2)
    }
  }
  return undefined
}

const isReadOnlyShellLine = (command: string, dialect: ShellDialect): boolean => {
  const stages = splitStages(command, dialect)
  return stages !== undefined && stages.length > 0
    && stages.every((stage) => isReadOnlyStage(stage, dialect))
}

const isReadOnlyStage = (tokens: ReadonlyArray<string>, _dialect: ShellDialect): boolean => {
  const headToken = tokens[0]
  if (headToken === undefined || headToken === "") return false
  const head = commandHeadName(headToken)
  const rest = tokens.slice(1)
  const lower = rest.map((token) => token.toLowerCase())
  if (head === "git") return isReadOnlyGit(lower)
  if (head === "npm" || head === "pnpm" || head === "yarn" || head === "bun") {
    return isReadOnlyPkg(lower)
  }
  if (INTERPRETERS.has(head)) {
    return rest.length >= 1 && /^(-v|--version|--help|-h)$/i.test(rest[0] ?? "")
  }
  if (head === "find" && rest.some((token) => FIND_WRITE.has(token.toLowerCase()))) return false
  if (head === "fd" && rest.some((token) => FD_WRITE.has(token))) return false
  if (
    head === "sed" && rest.some((token) => /^-.*i/.test(token) || token.startsWith("--in-place"))
  ) {
    return false
  }
  if (
    (head === "sort" || head === "tree")
    && rest.some((token) => token === "-o" || token === "--output" || token.startsWith("--output="))
  ) {
    return false
  }
  if (
    head === "awk"
    && rest.some((token) => token === "-i" || token.startsWith("-v") && token.includes("system"))
  ) {
    return false
  }
  return READONLY_HEADS.has(head)
}

const isReadOnlyGit = (args: ReadonlyArray<string>): boolean => {
  const sub = args[0] ?? ""
  if (sub === "" || sub.startsWith("-")) return false
  if (
    args.some((token) =>
      token === "--output" || token.startsWith("--output=") || token === "--output-directory"
    )
  ) {
    return false
  }
  if (sub === "grep" && args.some((token) => token === "-O" || token.startsWith("--open"))) {
    return false
  }
  if (sub === "branch") {
    return args.length === 1
      || args.slice(1).every((token) =>
        token.startsWith("-") && !["-d", "-D", "-m", "-M"].includes(token)
      )
  }
  if (sub === "remote") {
    const action = args.slice(1).find((token) => !token.startsWith("-"))
    return action === undefined || action === "show" || action === "get-url"
  }
  if (sub === "config") {
    return args.slice(1).some((token) =>
      token === "-l" || token === "--list" || token.startsWith("--get")
    )
  }
  return GIT_READONLY.has(sub)
}

const isReadOnlyPkg = (args: ReadonlyArray<string>): boolean => {
  const sub = args[0] ?? ""
  if (!PKG_READONLY.has(sub)) return false
  if (sub === "audit" && args.some((token) => token === "fix" || token.startsWith("--fix"))) {
    return false
  }
  return true
}

const splitStages = (command: string, dialect: ShellDialect): Array<Array<string>> | undefined => {
  const stages: Array<Array<string>> = []
  let stage: Array<string> = []
  let token = ""
  let started = false
  let quote: "'" | '"' | undefined

  const finishToken = () => {
    if (!started) return
    stage.push(token)
    token = ""
    started = false
  }
  const finishStage = (): boolean => {
    finishToken()
    if (stage.length === 0) return false
    stages.push(stage)
    stage = []
    return true
  }

  for (let i = 0; i < command.length; i++) {
    const ch = command[i]!
    if (quote === "'") {
      if (ch === "'") {
        if (dialect === "powershell" && command[i + 1] === "'") {
          token += "'"
          i++
        } else {
          quote = undefined
        }
      } else token += ch
      continue
    }
    if (quote === '"') {
      if (ch === '"') {
        quote = undefined
        continue
      }
      if (dialect !== "cmd" && (ch === "$" || ch === "`")) return undefined
      if (dialect === "cmd" && (ch === "%" || ch === "!")) return undefined
      token += ch
      continue
    }
    if (ch === "\r" || ch === "\n") return undefined
    if (/\s/.test(ch)) {
      finishToken()
      continue
    }
    if (ch === "'" && dialect !== "cmd") {
      quote = "'"
      started = true
      continue
    }
    if (ch === '"') {
      quote = '"'
      started = true
      continue
    }
    if (ch === "|") {
      if (!finishStage()) return undefined
      if (command[i + 1] === "|") i++
      continue
    }
    if (ch === "&") {
      if (command[i + 1] === "&") {
        if (!finishStage()) return undefined
        i++
        continue
      }
      if (dialect === "cmd") {
        if (!finishStage()) return undefined
        continue
      }
      return undefined
    }
    if (ch === ";") {
      if (!finishStage()) return undefined
      continue
    }
    if ("<>(){}$#`".includes(ch)) return undefined
    if (ch === "%" && dialect === "cmd") return undefined
    if (ch === "\\" && dialect === "posix") {
      const next = command[i + 1]
      if (next === undefined || next === "\r" || next === "\n") return undefined
      token += next
      started = true
      i++
      continue
    }
    if (ch === "`" && dialect === "powershell") {
      const next = command[i + 1]
      if (next === undefined || next === "\r" || next === "\n") return undefined
      token += next
      started = true
      i++
      continue
    }
    if (ch === "^" && dialect === "cmd") {
      const next = command[i + 1]
      if (next === undefined || next === "\r" || next === "\n") return undefined
      token += next
      started = true
      i++
      continue
    }
    token += ch
    started = true
  }
  if (quote !== undefined) return undefined
  finishToken()
  if (stage.length > 0) stages.push(stage)
  else if (!command.trimEnd().endsWith(";")) return undefined
  return stages
}
