import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process"
import { connectBeardAcp, type BeardAcp } from "./client.js"
import { encodeNdjson } from "./ndjson.js"

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
}): SpawnedAgent => {
  const args = input.args ?? GROK_AGENT_STDIO_ARGS
  const child = spawn(input.command, [...args], {
    cwd: input.cwd,
    env: input.env,
    stdio: ["pipe", "pipe", "pipe"]
  })
  const beard = connectBeardAcp({
    onOutgoing: (message) => {
      child.stdin.write(encodeNdjson(message))
    }
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
  spawned.beard.connection.close()
  spawned.child.kill()
}
