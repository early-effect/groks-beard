package groksbeard.core

import zio.json.ast.Json

final class FakeAgent(
    val sessionId: String = "sess_test",
    pairSetModeWithTerminal: Boolean = false,
    lockLoad: Boolean = false,
):
  def replies(msg: Rpc): List[Rpc] =
    msg match
      case Rpc.Request(id, method, _) => repliesFor(id, method)
      case _                          => Nil

  def encodeReplies(msg: Rpc): String =
    Ndjson.encodeChunk(replies(msg).map(Rpc.toLine))

  private def repliesFor(id: Json, method: String): List[Rpc] =
    method match
      case "initialize" =>
        List(
          Rpc.ok(
            id,
            Json.Obj(
              "protocolVersion"   -> Json.Num(1),
              "agentCapabilities" -> Json.Obj("loadSession" -> Json.Bool(true)),
            ),
          )
        )
      case "session/new" =>
        List(
          Rpc.notify(
            "session/update",
            Json.Obj(
              "sessionId" -> Json.Str(sessionId),
              "update"    -> Json.Obj(
                "sessionUpdate"     -> Json.Str("available_commands_update"),
                "availableCommands" -> Json.Arr(
                  Json.Obj("name" -> Json.Str("compact"), "description" -> Json.Str("Compact context")),
                  Json.Obj(
                    "name"        -> Json.Str("always-approve"),
                    "description" -> Json.Str("Skip permission prompts"),
                  ),
                ),
              ),
            ),
          ),
          Rpc.ok(
            id,
            Json.Obj(
              "sessionId" -> Json.Str(sessionId),
              "modes"     -> Json.Obj(
                "currentModeId"  -> Json.Str("normal"),
                "availableModes" -> Json.Arr(
                  Json.Obj("id" -> Json.Str("normal"), "name"         -> Json.Str("Normal")),
                  Json.Obj("id" -> Json.Str("plan"), "name"           -> Json.Str("Plan")),
                  Json.Obj("id" -> Json.Str("auto"), "name"           -> Json.Str("Auto")),
                  Json.Obj("id" -> Json.Str("always-approve"), "name" -> Json.Str("Always approve")),
                ),
              ),
            ),
          ),
        )
      case "session/load" =>
        if lockLoad then List(Rpc.fail(id, Rpc.MethodNotFound, "session locked"))
        else List(Rpc.ok(id, Json.Obj("sessionId" -> Json.Str(sessionId))))
      case "session/set_mode" =>
        val result = Rpc.ok(id, Json.Obj())
        if !pairSetModeWithTerminal then List(result)
        else
          List(
            result,
            Rpc.Request(
              Json.Str("term-1"),
              "terminal/create",
              Json.Obj(
                "sessionId" -> Json.Str(sessionId),
                "command"   -> Json.Str("rm"),
                "args"      -> Json.Arr(Json.Str("-rf"), Json.Str("/tmp/beard-probe")),
              ),
            ),
          )
        end if
      case "session/prompt" =>
        List(
          thought("Considering the selection.\n"),
          thought("Then I'll answer.\n"),
          agent("hello"),
          Rpc.notify(
            "session/update",
            Json.Obj(
              "sessionId" -> Json.Str(sessionId),
              "update"    -> Json.Obj(
                "sessionUpdate" -> Json.Str("tool_call"),
                "toolCallId"    -> Json.Str("call_1"),
                "title"         -> Json.Str("Edit"),
                "kind"          -> Json.Str("edit"),
                "status"        -> Json.Str("pending"),
              ),
            ),
          ),
          Rpc.ok(id, Json.Obj("stopReason" -> Json.Str("end_turn"))),
        )
      case _ =>
        List(Rpc.fail(id, Rpc.MethodNotFound, s"Method not found: $method"))

  private def thought(text: String): Rpc.Notify =
    chunk("agent_thought_chunk", text)

  private def agent(text: String): Rpc.Notify =
    chunk("agent_message_chunk", text)

  private def chunk(kind: String, text: String): Rpc.Notify =
    Rpc.notify(
      "session/update",
      Json.Obj(
        "sessionId" -> Json.Str(sessionId),
        "update"    -> Json.Obj(
          "sessionUpdate" -> Json.Str(kind),
          "content"       -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text)),
        ),
      ),
    )
end FakeAgent
