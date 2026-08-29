import {
  ChildProcessTerminalManager,
  resolveAgentShell,
  type TerminalManager,
} from "@groks-beard/acp"

export const createHostTerminalManager = (
  cwd: string,
  env: NodeJS.ProcessEnv = process.env,
  win = process.platform === "win32",
): TerminalManager =>
  new ChildProcessTerminalManager({
    cwd,
    env,
    win,
    shell: resolveAgentShell(env as Record<string, string | undefined>, win),
  })
