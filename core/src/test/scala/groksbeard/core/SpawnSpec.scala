package groksbeard.core

import zio.test.*

object SpawnSpec extends ZIOSpecDefault:
  def spec =
    suite("Spawn")(
      test("share is agent --leader stdio without yolo") {
        val shared  = Spawn.grokAgentStdioArgs()
        val trusted = Spawn.grokAgentStdioArgs(trustFolder = true)
        assertTrue(
          Spawn.AgentStdio == List("agent", "stdio"),
          shared == List("agent", "--leader", "stdio"),
          trusted == List("--trust", "agent", "--leader", "stdio"),
          Spawn.assertNoYoloArgs(shared),
          Spawn.assertNoYoloArgs(trusted),
          !shared.contains("--no-leader"),
          Spawn.backendLabel(true) == "Shared Grok",
        )
      },
      test("private is agent --no-leader stdio") {
        val isolated = Spawn.grokAgentStdioArgs(shareBackend = false)
        val trusted  = Spawn.grokAgentStdioArgs(trustFolder = true, shareBackend = false)
        assertTrue(
          isolated == List("agent", "--no-leader", "stdio"),
          trusted == List("--trust", "agent", "--no-leader", "stdio"),
          Spawn.assertNoYoloArgs(isolated),
          !isolated.contains("--leader"),
          Spawn.backendLabel(false) == "Private agent",
        )
      },
    )
end SpawnSpec
