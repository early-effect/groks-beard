package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object SessionUpdateSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionUpdate")(
      test("thought and agent chunks become HostMsg") {
        val thought = SessionUpdate.hostMsgs(chunk("agent_thought_chunk", "hmm"), "t1")
        val agent   = SessionUpdate.hostMsgs(chunk("agent_message_chunk", "hi"), "t1")
        val user    = SessionUpdate.hostMsgs(chunk("user_message_chunk", "hello from disk"), "t1")
        assertTrue(
          thought == List(HostMsg.ThoughtChunk("t1", "hmm")),
          agent == List(HostMsg.AgentChunk("t1", "hi")),
          user == List(HostMsg.UserMessage("t1", "hello from disk")),
        )
      },
      test("available_commands_update becomes commands") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "update" -> Json.Obj(
              "sessionUpdate"     -> Json.Str("available_commands_update"),
              "availableCommands" -> Json.Arr(
                Json.Obj("name" -> Json.Str("compact"), "description" -> Json.Str("Compact context"))
              ),
            )
          ),
          "t0",
        )
        assertTrue(msgs == List(HostMsg.AvailableCommands(List(SlashCommand("compact", "Compact context")))))
      },
      test("usage_update becomes occupancy on sessionMeta") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "sessionId" -> Json.Str("sess_test"),
            "update"    -> Json.Obj(
              "sessionUpdate" -> Json.Str("usage_update"),
              "used"          -> Json.Num(80),
              "size"          -> Json.Num(500),
            ),
          ),
          "t1",
        )
        assertTrue(msgs == List(HostMsg.SessionMeta("", "", "", occupancy = Some(Occupancy(80, 500)))))
      },
      test("execute tool_call keeps the shell command as input") {
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val msgs    = SessionUpdate.hostMsgs(executeStart("call_1", command), "t1")
        val row     = msgs.collectFirst { case HostMsg.ToolCall(_, tool) => tool }
        assertTrue(
          row.exists(_.title == "run_terminal_command"),
          row.exists(_.input.contains(command)),
          row.exists(_.output.isEmpty),
        )
      },
      test("in-progress execute output is watched before the command finishes") {
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stream  = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        val start   = SessionUpdate.hostMsgs(executeStart("call_1", command), "t1")
        val live    = SessionUpdate.hostMsgs(executeProgress("call_1", command, stream), "t1")
        val model   = (start ++ live).foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val row     = model.turns.head.tools.head
        val act     = TurnActivity.fromTurn(model.turns.head, 0)
        assertTrue(
          row.input.contains(command),
          row.output.exists(_.contains("Darwin")),
          ToolStatus.isLive(row.status),
          act.kind == ActivityKind.Execute,
          act.detail.exists(_.contains("Darwin")),
          !act.detail.exists(_.contains("echo beard-terminal-probe")),
        )
      },
      test("completed execute tool_call_update keeps nested stdout") {
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stdout  = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        val start   = SessionUpdate.hostMsgs(executeStart("call_1", command), "t1")
        val done    = SessionUpdate.hostMsgs(executeDone("call_1", command, stdout), "t1")
        val model   = (start ++ done).foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val row     = model.turns.head.tools.head
        assertTrue(
          row.title == "run_terminal_command",
          row.kind == ToolKind.Execute,
          row.input.contains(command),
          row.output.exists(_.contains("beard-terminal-probe")),
          row.output.exists(_.contains("Darwin")),
        )
      },
      test("a grok completed execute without kind or title still expands to command and stdout") {
        val command   = "echo beard-terminal-probe\npwd\nuname -s"
        val stdout    = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        val start     = SessionUpdate.hostMsgs(executeStart("call_1", command), "t1")
        val described =
          SessionUpdate.hostMsgs(executeDescribed("call_1", command, "Run exact user-requested probe command"), "t1")
        val done  = SessionUpdate.hostMsgs(executeFinished("call_1", command, stdout), "t1")
        val live  = (start ++ described).foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val model = done.foldLeft(live)(ChatModel.applyMsg)
        val row   = model.turns.head.tools.head
        assertTrue(
          ToolStatus.isLive(live.turns.head.tools.head.status),
          live.turns.head.tools.head.input.contains(command),
          !live.turns.head.tools.head.output.exists(_.contains("Run exact")),
          row.title == "run_terminal_command",
          row.kind == ToolKind.Execute,
          row.status == ToolStatus.Completed,
          row.input.contains(command),
          row.output.exists(_.contains("beard-terminal-probe")),
          row.output.exists(_.contains("Darwin")),
          !row.output.exists(_.contains("exit: 0")),
        )
      },
      test("a live grok run_terminal_command tool_call becomes ToolCall") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "sessionId" -> Json.Str("sess_test"),
            "update"    -> Json.Obj(
              "sessionUpdate" -> Json.Str("tool_call"),
              "toolCallId"    -> Json.Str("call-92323aaa-c2e0-44e8-9bb8-84804ea1684b-0"),
              "title"         -> Json.Str("run_terminal_command"),
              "rawInput"      -> Json.Obj(
                "command"     -> Json.Str("echo hi"),
                "description" -> Json.Str("Echo"),
                "timeout"     -> Json.Num(15000),
              ),
            ),
          ),
          "t1",
        )
        val row = msgs.collectFirst { case HostMsg.ToolCall(_, tool) => tool }
        assertTrue(
          row.exists(_.id.value == "call-92323aaa-c2e0-44e8-9bb8-84804ea1684b-0"),
          row.exists(_.title == "run_terminal_command"),
          row.exists(_.input.contains("echo hi")),
        )
      },
      test("unknown sessionUpdate is ignored") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "update" -> Json.Obj("sessionUpdate" -> Json.Str("brand_new_event"), "extra" -> Json.Bool(true))
          ),
          "t1",
        )
        assertTrue(msgs.isEmpty)
      },
      test("plan sessionUpdate becomes todos") {
        val msgs = SessionUpdate.hostMsgs(
          Json.Obj(
            "sessionId" -> Json.Str("sess_test"),
            "update"    -> Json.Obj(
              "sessionUpdate" -> Json.Str("plan"),
              "entries"       -> Json.Arr(
                Json.Obj(
                  "content"  -> Json.Str("Checkout branch"),
                  "priority" -> Json.Str("medium"),
                  "status"   -> Json.Str("in_progress"),
                ),
                Json.Obj(
                  "content"  -> Json.Str("Write tests"),
                  "priority" -> Json.Str("high"),
                  "status"   -> Json.Str("pending"),
                ),
              ),
            ),
          ),
          "t1",
        )
        assertTrue(
          msgs == List(
            HostMsg.Todos(
              List(
                TodoEntry("Checkout branch", Todos.InProgress, "medium"),
                TodoEntry("Write tests", Todos.Pending, "high"),
              )
            )
          )
        )
      },
    )

  private def executeStart(id: String, command: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str("tool_call"),
        "toolCallId"    -> Json.Str(id),
        "title"         -> Json.Str("run_terminal_command"),
        "rawInput"      -> Json.Obj(
          "command"     -> Json.Str(command),
          "description" -> Json.Str("Run exact probe command as requested"),
        ),
      ),
    )

  private def executeDescribed(id: String, command: String, description: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str("tool_call_update"),
        "toolCallId"    -> Json.Str(id),
        "kind"          -> Json.Str("execute"),
        "title"         -> Json.Str(s"Execute `$command`"),
        "rawInput"      -> Json.Obj("command" -> Json.Str(command), "description" -> Json.Str(description)),
        "content"       -> Json.Arr(
          Json.Obj(
            "type"    -> Json.Str("content"),
            "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(description)),
          )
        ),
      ),
    )

  private def executeFinished(id: String, command: String, stdout: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str("tool_call_update"),
        "toolCallId"    -> Json.Str(id),
        "status"        -> Json.Str("completed"),
        "content"       -> Json.Arr(
          Json.Obj(
            "type"    -> Json.Str("content"),
            "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(stdout)),
          )
        ),
        "rawOutput" -> Json.Obj(
          "output_for_prompt" -> Json.Str(s"exit: 0\n$stdout"),
          "command"           -> Json.Str(command),
          "exit_code"         -> Json.Num(0),
        ),
      ),
    )

  private def executeProgress(id: String, command: String, stdout: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str("tool_call_update"),
        "toolCallId"    -> Json.Str(id),
        "kind"          -> Json.Str("execute"),
        "status"        -> Json.Str("in_progress"),
        "title"         -> Json.Str(s"Execute `$command`"),
        "rawInput"      -> Json.Obj(
          "command"     -> Json.Str(command),
          "description" -> Json.Str("Run exact probe command as requested"),
        ),
        "content" -> Json.Arr(
          Json.Obj(
            "type"    -> Json.Str("content"),
            "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(stdout)),
          )
        ),
      ),
    )

  private def executeDone(id: String, command: String, stdout: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str("tool_call_update"),
        "toolCallId"    -> Json.Str(id),
        "kind"          -> Json.Str("execute"),
        "status"        -> Json.Str("completed"),
        "title"         -> Json.Str(s"Execute `$command`"),
        "content"       -> Json.Arr(
          Json.Obj(
            "type"    -> Json.Str("content"),
            "content" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(stdout)),
          )
        ),
        "rawOutput" -> Json.Obj(
          "output_for_prompt" -> Json.Str(s"exit: 0\n$stdout"),
          "command"           -> Json.Str(command),
          "exit_code"         -> Json.Num(0),
        ),
      ),
    )

  private def chunk(kind: String, text: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str("sess_test"),
      "update"    -> Json.Obj(
        "sessionUpdate" -> Json.Str(kind),
        "content"       -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text)),
      ),
    )
end SessionUpdateSpec
