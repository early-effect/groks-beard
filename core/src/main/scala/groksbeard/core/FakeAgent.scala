package groksbeard.core

import zio.json.*

final class FakeAgent(
    val sessionId: String = "sess_test",
    pairSetModeWithTerminal: Boolean = false,
    lockLoad: Boolean = false,
    hangPrompt: Boolean = false,
):
  def replies(msg: Rpc): List[Rpc] =
    msg match
      case Rpc.Request(id, method, params) => repliesFor(id, method, params)
      case _                               => Nil

  def encodeReplies(msg: Rpc): String =
    Ndjson.encodeChunk(replies(msg).map(Rpc.toLine))

  private def repliesFor(id: RpcId, method: String, params: zio.json.ast.Json): List[Rpc] =
    method match
      case "initialize" =>
        List(Rpc.ok(id, InitializeResult(1, AgentCapabilities(loadSession = true)).asJson))
      case "session/new" =>
        List(
          Rpc.notifyOf(
            "session/update",
            AcpSessionNotify(
              sessionId,
              AcpUpdate.Commands(
                List(
                  SlashCommand("compact", "Compact context"),
                  SlashCommand("always-approve", "Skip permission prompts"),
                )
              ),
            ),
          ),
          Rpc.ok(
            id,
            SessionNewResult(
              sessionId,
              Some(
                SessionModeState(
                  "normal",
                  List(
                    ModeOption("normal", "Normal"),
                    ModeOption("plan", "Plan"),
                    ModeOption("auto", "Auto"),
                    ModeOption("always-approve", "Always approve"),
                  ),
                )
              ),
              Some(
                SessionModelState(
                  "grok-4.6",
                  List(
                    ModelOption("grok-4.6", "Grok 4.6"),
                    ModelOption("grok-code-fast-1", "Grok Code Fast"),
                  ),
                )
              ),
            ).asJson,
          ),
        )
      case "session/load" =>
        if lockLoad then List(Rpc.fail(id, Rpc.MethodNotFound, "session locked"))
        else
          val sid = params.as[SessionLoadParams].toOption.map(_.sessionId).filter(_.nonEmpty).getOrElse(sessionId)
          List(
            chunk(AcpUpdate.User(AcpContent.Text("hello from disk")), sid),
            chunk(AcpUpdate.Agent(AcpContent.Text("welcome back")), sid),
            Rpc.ok(id, SessionLoadResult(sid).asJson),
          )
      case "session/set_model" =>
        List(Rpc.ok(id, EmptyObject().asJson))
      case "session/set_mode" =>
        val result = Rpc.ok(id, EmptyObject().asJson)
        if !pairSetModeWithTerminal then List(result)
        else
          List(
            result,
            Rpc.request(
              RpcId.Str("term-1"),
              "terminal/create",
              TerminalCreateParams(sessionId, "rm", List("-rf", "/tmp/beard-probe")),
            ),
          )
        end if
      case "session/prompt" if hangPrompt =>
        Nil
      case "session/prompt" =>
        List(
          thought("Considering the selection.\n"),
          thought("Then I'll answer.\n"),
          agent("hello"),
          Rpc.request(
            RpcId.Str("perm-1"),
            "session/request_permission",
            PermissionRequestParams(
              toolCall = AcpToolCall(
                toolCallId = "call_1",
                title = "Edit Main.scala",
                kind = "edit",
                status = "pending",
                content = List(
                  AcpContent.Diff(
                    path = "/tmp/Main.scala",
                    oldText = Some("object Main"),
                    newText = Some("object Main:\n  def run = ()"),
                  )
                ),
              ),
              options = List(PermissionOption("allow-once", "Allow once", "allow_once")),
            ),
          ),
          Rpc.notifyOf(
            "session/update",
            AcpSessionNotify(
              sessionId,
              AcpUpdate.ToolCall(
                toolCallId = "call_1",
                title = "Edit Main.scala",
                kind = "edit",
                status = "pending",
                content = List(
                  AcpContent.Diff(
                    path = "/tmp/Main.scala",
                    oldText = Some("object Main"),
                    newText = Some("object Main:\n  def run = ()"),
                  )
                ),
              ),
            ),
          ),
          Rpc.ok(id, SessionPromptResult("end_turn").asJson),
        )
      case _ =>
        List(Rpc.fail(id, Rpc.MethodNotFound, s"Method not found: $method"))

  private def thought(text: String): Rpc.Notify =
    chunk(AcpUpdate.Thought(AcpContent.Text(text)))

  private def agent(text: String): Rpc.Notify =
    chunk(AcpUpdate.Agent(AcpContent.Text(text)))

  private def chunk(update: AcpUpdate, sid: String = sessionId): Rpc.Notify =
    Rpc.notifyOf("session/update", AcpSessionNotify(sid, update))
end FakeAgent
