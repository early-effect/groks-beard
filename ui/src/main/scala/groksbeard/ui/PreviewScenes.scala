package groksbeard.ui

import groksbeard.core.*

object PreviewScenes:
  val mcps: List[McpServerView] = List(
    McpServerView(
      "metals",
      "http",
      "http://localhost:56126/mcp",
      source = "/repo/.mcp.json",
      enabled = true,
    ),
    McpServerView(
      "atlassian",
      "http",
      "https://mcp.atlassian.com/v1/mcp",
      source = "/Users/russ/.claude.json",
      vendor = Some("claude"),
      enabled = false,
    ),
  )

  private val allow =
    PermissionOption("allow", "Allow", PermissionKind.AllowOnce)
  private val reject =
    PermissionOption("reject", "Reject", PermissionKind.RejectOnce)

  def seed(scene: Scene): ChatModel =
    scene match
      case Scene.Transcript =>
        ChatModel.empty.copy(
          occupancy = Some(Occupancy(12_000, 500_000)),
          turns = List(
            TurnView(
              id = TurnId("t1"),
              user = Some(TurnUser("Summarize Main.scala")),
              agent = "Here is a **short** look at `Main.scala`.\n\n- entry is `main`\n- it boots Ascent",
              tools = List(
                ToolRow(
                  ToolCallId("read-1"),
                  "Read Main.scala",
                  ToolKind.Read,
                  ToolStatus.Completed,
                  input = Some("src/Main.scala"),
                  output = Some("object Main"),
                ),
                ToolRow(
                  ToolCallId("term-1"),
                  "run_terminal_command",
                  ToolKind.Execute,
                  ToolStatus.Completed,
                  input = Some("echo beard-terminal-probe\npwd\nuname -s"),
                  output = Some("beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"),
                ),
              ),
              thought = "Need the file first.",
              stopReason = Some(StopReason.EndTurn),
            )
          ),
        )
      case Scene.Permission =>
        ChatModel.empty.copy(
          occupancy = Some(Occupancy(80, 100)),
          turns = List(
            TurnView(
              id = TurnId("t2"),
              user = Some(TurnUser("Edit Main.scala")),
              agent = "I'll patch the file.",
              tools = List(
                ToolRow(
                  ToolCallId("edit-1"),
                  "Edit Main.scala",
                  ToolKind.Edit,
                  ToolStatus.Pending,
                  additions = Some(3),
                  deletions = Some(1),
                )
              ),
            )
          ),
          permission = Some(
            PermissionCard(
              RequestId("perm-1"),
              ToolCallId("edit-1"),
              "Edit src/Main.scala",
              List(allow, reject),
              hasDiff = true,
            )
          ),
        )
      case Scene.Plan =>
        ChatModel.empty.copy(
          plan = Some(
            PlanCard(
              RequestId("plan-1"),
              """# Plan
                |
                |1. Port transcript
                |2. Wire cards
                |""".stripMargin,
            )
          )
        )
      case Scene.Question =>
        ChatModel.empty.copy(
          question = Some(
            QuestionCard(
              RequestId("q-1"),
              List(
                AgentQuestion(
                  "style",
                  "How should the transcript look?",
                  List(QuestionOption("dense", "Dense"), QuestionOption("roomy", "Roomy")),
                ),
                AgentQuestion(
                  "extras",
                  "Which extras?",
                  List(QuestionOption("wrap", "Wrap"), QuestionOption("pin", "Pin"), QuestionOption("copy", "Copy")),
                  allowMultiple = true,
                ),
                AgentQuestion(
                  "note",
                  "Anything else?",
                  List(QuestionOption("skip", "Nothing")),
                  allowFreeText = true,
                ),
              ),
            )
          )
        )
      case Scene.Elicit =>
        ChatModel.empty.copy(
          elicit =
            Some(ElicitCard(RequestId("el-1"), "docs", ElicitMode.Url, "Open docs?", Some("https://example.com")))
        )
      case Scene.Changes =>
        ChatModel.empty.copy(
          turns = List(
            TurnView(
              id = TurnId("t3"),
              user = Some(TurnUser("Patch Main.scala")),
              agent = "Edited `Main.scala`.",
              tools = List(
                ToolRow(
                  ToolCallId("call_1"),
                  "Edit Main.scala",
                  ToolKind.Edit,
                  ToolStatus.Completed,
                  additions = Some(2),
                  deletions = Some(1),
                  input = Some("/tmp/Main.scala"),
                )
              ),
              stopReason = Some(StopReason.EndTurn),
            )
          ),
          changes = Some(
            ChangesSummary(
              1,
              2,
              1,
              List(
                ChangeFileView(
                  "/tmp/Main.scala",
                  ChangeKind.Modify,
                  2,
                  1,
                  wholeFile = true,
                  turnId = TurnId("t3"),
                  turnTitle = "Patch Main.scala",
                )
              ),
            )
          ),
        )
      case Scene.Queue =>
        ChatModel.empty.copy(
          inSession = true,
          title = "Queue",
          turns = List(
            TurnView(
              id = TurnId("t-run"),
              user = Some(TurnUser("Ship the queue pane")),
              agent = "Working on it.",
            )
          ),
          queue = List(
            QueuedPrompt(QueueId("q1"), "then run the tests"),
            QueuedPrompt(QueueId("q2"), "then open the PR"),
          ),
        )
      case Scene.Todos =>
        ChatModel.empty.copy(
          inSession = true,
          title = "Todos",
          todos = List(
            TodoEntry("Checkout the branch", Todos.Completed, TodoPriority.Medium, Some("1")),
            TodoEntry("Wire ACP plan updates", Todos.InProgress, TodoPriority.High, Some("2")),
            TodoEntry("Match the TUI pane", Todos.Pending, TodoPriority.Medium, Some("3")),
          ),
        )
      case Scene.Mcps =>
        ChatModel.empty.copy(
          inSession = true,
          title = "MCP",
          commands = SessionCommands.merge(Nil),
          mcps = PreviewScenes.mcps,
        )
      case Scene.Palette =>
        ChatModel.empty.copy(commands = SessionCommands.merge(List(SlashCommand("compact", "Compact context"))))
      case Scene.Resume =>
        ChatModel.empty.copy(
          sessionId = SessionId("preview"),
          pickerOpen = true,
          sessions = List(
            SessionRow(SessionId("preview"), "New session", activityMs = 20),
            SessionRow(
              SessionId("disk-1"),
              "Effect-TS Grok Build VS Code Plugin Plan",
              activityMs = 10,
              lastTurn = Some("Continue the plan"),
            ),
            SessionRow(SessionId("disk-2"), "Ascent chat chrome", activityMs = 5, summary = Some("Composer and cards")),
          ),
        )
      case _ => ChatModel.empty
end PreviewScenes

object PreviewDiffs:
  val MainOld: String = "object Main\n"
  val MainNew: String = "object Main:\n  def run = ()\n"
  val MainPath        = "/tmp/Main.scala"
