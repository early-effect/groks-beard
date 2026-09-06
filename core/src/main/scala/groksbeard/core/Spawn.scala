package groksbeard.core

object Spawn:
  val AgentStdio: List[String] = List("agent", "stdio")

  def grokAgentStdioArgs(trustFolder: Boolean = false): List[String] =
    if trustFolder then "--trust" :: AgentStdio else AgentStdio

  def assertNoYoloArgs(args: List[String]): Boolean =
    !args.contains("--always-approve") && !args.contains("--yolo") && !args.contains("--no-leader")
end Spawn
