import { expect, it } from "@effect/vitest"
import { Effect } from "effect"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { newSession, promptSession } from "../src/sessions.ts"
import {
  assertNoYoloArgs,
  GROK_AGENT_STDIO_ARGS,
  killSpawnedAgent,
  spawnGrokAgentStdio,
} from "../src/spawn.ts"

const fixture = join(dirname(fileURLToPath(import.meta.url)), "fixtures/fake-grok.mjs")

it("stdio spawn args are agent stdio without yolo or no-leader", () => {
  expect([...GROK_AGENT_STDIO_ARGS]).toEqual(["agent", "stdio"])
  expect(assertNoYoloArgs(GROK_AGENT_STDIO_ARGS)).toBe(true)
})

it("kills the grok child even when terminal.dispose throws", () => {
  let killed = false
  expect(() =>
    killSpawnedAgent({
      child: {
        kill: () => {
          killed = true
        },
      } as never,
      beard: {
        terminal: {
          dispose: () => {
            throw new Error("in-flight release")
          },
        },
        connection: { close: () => undefined },
      } as never,
    })
  ).not.toThrow()
  expect(killed).toBe(true)
})

it("spawns the fake stdio agent, creates a session, and prompts", async () => {
  const spawned = spawnGrokAgentStdio({
    command: process.execPath,
    args: [fixture, ...GROK_AGENT_STDIO_ARGS],
    cwd: process.cwd(),
  })
  try {
    await spawned.beard.agent.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: {},
      clientInfo: { name: "groks-beard", title: "Grok's Beard", version: "0.0.0" },
    })
    const created = await Effect.runPromise(newSession(spawned.beard.agent, "/tmp/proj"))
    expect(created.sessionId).toBe("sess_fake")
    const prompted = await Effect.runPromise(
      promptSession(spawned.beard.agent, created.sessionId, "@src/Foo.scala:10-50\n\nexplain"),
    )
    expect(prompted.stopReason).toBe("end_turn")
  } finally {
    killSpawnedAgent(spawned)
  }
})
