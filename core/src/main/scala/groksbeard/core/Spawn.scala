package groksbeard.core

object Spawn:
  val AgentStdio: List[String] = List("agent", "stdio")

  /** `--leader` joins `~/.grok/leader.sock` or auto-starts that leader. `--no-leader` is a private backend. */
  def grokAgentStdioArgs(trustFolder: Boolean = false, shareBackend: Boolean = true): List[String] =
    val trust  = if trustFolder then List("--trust") else Nil
    val leader = if shareBackend then List("--leader") else List("--no-leader")
    trust ::: "agent" :: leader ::: List("stdio")

  def assertNoYoloArgs(args: List[String]): Boolean =
    !args.contains("--always-approve") && !args.contains("--yolo")

  def backendLabel(shareBackend: Boolean): String =
    if shareBackend then "Shared Grok" else "Private agent"
end Spawn
