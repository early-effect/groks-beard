package groksbeard.core

import zio.test.*

object SpawnSpec extends ZIOSpecDefault:
  def spec =
    suite("Spawn")(
      test("stdio args are agent stdio without yolo or no-leader") {
        assertTrue(
          Spawn.AgentStdio == List("agent", "stdio"),
          Spawn.assertNoYoloArgs(Spawn.AgentStdio),
          Spawn.grokAgentStdioArgs() == List("agent", "stdio"),
          Spawn.grokAgentStdioArgs(trustFolder = true) == List("--trust", "agent", "stdio"),
          Spawn.assertNoYoloArgs(Spawn.grokAgentStdioArgs(trustFolder = true)),
        )
      }
    )
end SpawnSpec
