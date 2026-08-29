import { type ChildProcessWithoutNullStreams, spawn } from "node:child_process"
import { type BeardAcp, type BeardClientHandlers, connectBeardAcp } from "./client.js"
import { encodeNdjson } from "./ndjson.js"
import type { TerminalManager } from "./terminal-manager.js"

export const GROK_AGENT_STDIO_ARGS = ["agent", "stdio"] as const

export const assertNoYoloArgs = (args: ReadonlyArray<string>): boolean =>
  !args.includes("--always-approve") && !args.includes("--yolo") && !args.includes("--no-leader")

export type SpawnedAgent = {
  readonly child: ChildProcessWithoutNullStreams
  readonly beard: BeardAcp
}

export const spawnGrokAgentStdio = (input: {
  readonly command: string
  readonly cwd: string
  readonly env?: NodeJS.ProcessEnv
  readonly args?: ReadonlyArray<string>
  readonly onSessionUpdate?: BeardClientHandlers["onSessionUpdate"]
  readonly onPermission?: BeardClientHandlers["onPermission"]
  readonly onTerminalCreate?: BeardClientHandlers["onTerminalCreate"]
  readonly onExitPlanMode?: BeardClientHandlers["onExitPlanMode"]
  readonly onAskUserQuestion?: BeardClientHandlers["onAskUserQuestion"]
  readonly onElicit?: BeardClientHandlers["onElicit"]
  readonly terminal?: TerminalManager
}): SpawnedAgent => {
  const args = input.args ?? GROK_AGENT_STDIO_ARGS
  const child = spawn(input.command, [...args], {
    cwd: input.cwd,
    env: input.env,
    stdio: ["pipe", "pipe", "pipe"],
  })
  const beard = connectBeardAcp({
    onOutgoing: (message) => {
      child.stdin.write(encodeNdjson(message))
    },
    ...(input.onSessionUpdate !== undefined ? { onSessionUpdate: input.onSessionUpdate } : {}),
    ...(input.onPermission !== undefined ? { onPermission: input.onPermission } : {}),
    ...(input.onTerminalCreate !== undefined ? { onTerminalCreate: input.onTerminalCreate } : {}),
    ...(input.onExitPlanMode !== undefined ? { onExitPlanMode: input.onExitPlanMode } : {}),
    ...(input.onAskUserQuestion !== undefined
      ? { onAskUserQuestion: input.onAskUserQuestion }
      : {}),
    ...(input.onElicit !== undefined ? { onElicit: input.onElicit } : {}),
    ...(input.terminal !== undefined ? { terminal: input.terminal } : {}),
  })
  child.stdout.on("data", (chunk: Buffer) => {
    beard.transport.feedFromAgent(new Uint8Array(chunk))
  })
  child.stderr.on("data", () => undefined)
  child.on("error", (error) => {
    beard.transport.close(error)
  })
  return { child, beard }
}

export const killSpawnedAgent = (spawned: SpawnedAgent): void => {
  spawned.beard.terminal.dispose()
  spawned.beard.connection.close()
  spawned.child.kill()
}
