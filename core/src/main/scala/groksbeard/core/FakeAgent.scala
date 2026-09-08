package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final class FakeAgent(
    val sessionId: SessionId = SessionId("sess_test"),
    pairSetModeWithTerminal: Boolean = false,
    pairTerminal: TerminalCreateParams = TerminalCreateParams(command = "rm", args = List("-rf", "/tmp/beard-probe")),
    lockLoad: Boolean = false,
    hangPrompt: Boolean = false,
    rejectFork: Boolean = false,
    worktreeMeta: Boolean = false,
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
        val caps =
          if worktreeMeta then
            AgentCapabilities(
              loadSession = true,
              _meta = Some(Json.Obj("x.ai/git/worktree/create" -> Json.Bool(true))),
            )
          else AgentCapabilities(loadSession = true)
        List(Rpc.ok(id, InitializeResult(1, caps).asJson))
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
                  ModeId.Normal,
                  List(
                    ModeOption(ModeId.Normal, "Normal"),
                    ModeOption(ModeId.Plan, "Plan"),
                    ModeOption(ModeId.Auto, "Auto"),
                    ModeOption(ModeId.AlwaysApprove, "Always approve"),
                  ),
                )
              ),
              Some(
                SessionModelState(
                  ModelId("grok-4.6"),
                  List(
                    ModelOption(ModelId("grok-4.6"), "Grok 4.6", _meta = Some(Effort.grokMeta())),
                    ModelOption(ModelId("grok-code-fast-1"), "Grok Code Fast"),
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
            chunk(
              AcpUpdate.Plan(
                List(
                  TodoEntry("Replay the disk snapshot", Todos.Completed, TodoPriority.Medium),
                  TodoEntry("Continue the work", Todos.InProgress, TodoPriority.High),
                )
              ),
              sid,
            ),
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
              pairTerminal.copy(sessionId = if pairTerminal.sessionId.isEmpty then sessionId
              else pairTerminal.sessionId),
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
                toolCallId = ToolCallId("call_1"),
                title = "Edit Main.scala",
                kind = ToolKind.Edit,
                status = ToolStatus.Pending,
                content = List(
                  AcpContent.Diff(
                    path = "/tmp/Main.scala",
                    oldText = Some("object Main"),
                    newText = Some("object Main:\n  def run = ()"),
                  )
                ),
                locations = List(ToolLocation("/tmp/Main.scala", Some(1))),
              ),
              options = List(PermissionOption("allow-once", "Allow once", PermissionKind.AllowOnce)),
            ),
          ),
          Rpc.notifyOf(
            "session/update",
            AcpSessionNotify(
              sessionId,
              AcpUpdate.ToolCall(
                toolCallId = ToolCallId("call_1"),
                title = "Edit Main.scala",
                kind = ToolKind.Edit,
                status = ToolStatus.Pending,
                content = List(
                  AcpContent.Diff(
                    path = "/tmp/Main.scala",
                    oldText = Some("object Main"),
                    newText = Some("object Main:\n  def run = ()"),
                  )
                ),
                locations = List(ToolLocation("/tmp/Main.scala", Some(1))),
              ),
            ),
          ),
          Rpc.ok(id, SessionPromptResult(StopReason.EndTurn).asJson),
        )
      case "x.ai/rewind/points" | "_x.ai/rewind/points" =>
        List(
          Rpc.ok(
            id,
            RewindPointsResult(
              List(
                RewindPoint(0, "first prompt"),
                RewindPoint(1, "second prompt"),
              )
            ).asJson,
          )
        )
      case "x.ai/rewind/execute" | "_x.ai/rewind/execute" =>
        List(Rpc.ok(id, EmptyObject().asJson))
      case "x.ai/session/fork" | "_x.ai/session/fork" if rejectFork =>
        List(Rpc.fail(id, Rpc.MethodNotFound, s"Method not found: $method"))
      case "x.ai/session/fork" | "_x.ai/session/fork" =>
        val src = params.as[ForkSessionParams].toOption
        val kid = SessionId("sess_fork")
        List(
          Rpc.ok(
            id,
            ForkSessionResult(
              newSessionId = kid,
              chatMessagesCopied = 2,
              newCwd = src.map(_.newCwd).getOrElse("."),
              parentSessionId = src.map(_.sourceSessionId).getOrElse(sessionId),
            ).asJson,
          )
        )
      case _ =>
        List(Rpc.fail(id, Rpc.MethodNotFound, s"Method not found: $method"))

  private def thought(text: String): Rpc.Notify =
    chunk(AcpUpdate.Thought(AcpContent.Text(text)))

  private def agent(text: String): Rpc.Notify =
    chunk(AcpUpdate.Agent(AcpContent.Text(text)))

  private def chunk(update: AcpUpdate, sid: SessionId = sessionId): Rpc.Notify =
    Rpc.notifyOf("session/update", AcpSessionNotify(sid, update))
end FakeAgent
