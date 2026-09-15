package groksbeard.core

/** JSON-RPC method names. Use `.value` on the wire; never hand-write `"session/…"`. */
enum AcpMethod(val value: String, val aliases: String*):
  case Initialize        extends AcpMethod("initialize")
  case SessionNew        extends AcpMethod("session/new")
  case SessionLoad       extends AcpMethod("session/load")
  case SessionPrompt     extends AcpMethod("session/prompt")
  case SessionCancel     extends AcpMethod("session/cancel")
  case SessionSetMode    extends AcpMethod("session/set_mode")
  case SessionSetModel   extends AcpMethod("session/set_model")
  case SessionSetConfig  extends AcpMethod("session/set_config_option")
  case SessionList       extends AcpMethod("session/list")
  case SessionResume     extends AcpMethod("session/resume")
  case SessionClose      extends AcpMethod("session/close")
  case SessionUpdate     extends AcpMethod("session/update", "_x.ai/session/update", "x.ai/session/update")
  case RequestPermission extends AcpMethod("session/request_permission")
  case RewindPoints      extends AcpMethod("x.ai/rewind/points", "_x.ai/rewind/points")
  case RewindExecute     extends AcpMethod("x.ai/rewind/execute", "_x.ai/rewind/execute")
  case SessionFork       extends AcpMethod("x.ai/session/fork", "_x.ai/session/fork")
  case Interject         extends AcpMethod("_x.ai/interject", "x.ai/interject")
  case ExitPlanMode      extends AcpMethod("_x.ai/exit_plan_mode", "x.ai/exit_plan_mode")
  case AskUserQuestion   extends AcpMethod("_x.ai/ask_user_question", "x.ai/ask_user_question")
  case Elicit            extends AcpMethod("_x.ai/mcp/elicit", "x.ai/mcp/elicit")
  case ElicitCreate      extends AcpMethod("elicitation/create")
  case TerminalCreate    extends AcpMethod("terminal/create", "x.ai/terminal/create", "_x.ai/terminal/create")
  case TerminalOutput    extends AcpMethod("terminal/output", "x.ai/terminal/output", "_x.ai/terminal/output")
  case TerminalWait
      extends AcpMethod("terminal/wait_for_exit", "x.ai/terminal/wait_for_exit", "_x.ai/terminal/wait_for_exit")
  case TerminalKill    extends AcpMethod("terminal/kill", "x.ai/terminal/kill", "_x.ai/terminal/kill")
  case TerminalRelease extends AcpMethod("terminal/release", "x.ai/terminal/release", "_x.ai/terminal/release")
end AcpMethod

object AcpMethod:
  def parse(raw: String): Option[AcpMethod] =
    val n = canon(raw)
    AcpMethod.values.find(m => names(m).exists(s => canon(s) == n))

  def is(raw: String, method: AcpMethod): Boolean =
    parse(raw).contains(method)

  def isSessionNotify(raw: String): Boolean =
    is(raw, SessionUpdate)

  private def names(m: AcpMethod): Seq[String] =
    m.value +: m.aliases

  private def canon(raw: String): String =
    raw.stripPrefix("_")
end AcpMethod
