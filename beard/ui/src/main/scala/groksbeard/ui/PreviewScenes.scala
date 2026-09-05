package groksbeard.ui

import groksbeard.core.*

object PreviewScenes:
  private val allow =
    PermissionOption("allow", "Allow", "allow_once")
  private val reject =
    PermissionOption("reject", "Reject", "reject_once")

  def seed(scene: Scene): ChatModel =
    scene match
      case Scene.Transcript =>
        ChatModel.empty.copy(
          occupancy = Some(Occupancy(12_000, 500_000)),
          turns = List(
            TurnView(
              id = "t1",
              user = Some(TurnUser("Summarize Main.scala")),
              agent = "Here is a **short** look at `Main.scala`.\n\n- entry is `main`\n- it boots Ascent",
              tools = List(
                ToolRow(
                  "read-1",
                  "Read Main.scala",
                  "read",
                  "completed",
                  input = Some("src/Main.scala"),
                  output = Some("object Main"),
                )
              ),
              thought = "Need the file first.",
              stopReason = Some("end_turn"),
            )
          ),
        )
      case Scene.Permission =>
        ChatModel.empty.copy(
          occupancy = Some(Occupancy(80, 100)),
          turns = List(
            TurnView(
              id = "t2",
              user = Some(TurnUser("Edit Main.scala")),
              agent = "I'll patch the file.",
              tools =
                List(ToolRow("edit-1", "Edit Main.scala", "edit", "pending", additions = Some(3), deletions = Some(1))),
            )
          ),
          permission = Some(
            PermissionCard("perm-1", "edit-1", "Edit src/Main.scala", List(allow, reject), hasDiff = true)
          ),
        )
      case Scene.Plan =>
        ChatModel.empty.copy(
          plan = Some(
            PlanCard(
              "plan-1",
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
              "q-1",
              List(
                AgentQuestion(
                  "style",
                  "How should the transcript look?",
                  List(QuestionOption("dense", "Dense"), QuestionOption("roomy", "Roomy")),
                )
              ),
            )
          )
        )
      case Scene.Elicit =>
        ChatModel.empty.copy(
          elicit = Some(ElicitCard("el-1", "docs", "url", "Open docs?", Some("https://example.com")))
        )
      case Scene.Changes =>
        ChatModel.empty.copy(
          turns = List(
            TurnView(
              id = "t3",
              user = Some(TurnUser("Patch Main.scala")),
              agent = "Edited `Main.scala`.",
              tools = List(
                ToolRow(
                  "call_1",
                  "Edit Main.scala",
                  "edit",
                  "completed",
                  additions = Some(2),
                  deletions = Some(1),
                  input = Some("/tmp/Main.scala"),
                )
              ),
              stopReason = Some("end_turn"),
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
                  "modify",
                  2,
                  1,
                  wholeFile = true,
                  turnId = "t3",
                  turnTitle = "Patch Main.scala",
                )
              ),
            )
          ),
        )
      case Scene.Resume =>
        ChatModel.empty.copy(
          sessionId = "preview",
          pickerOpen = true,
          sessions = List(
            SessionRow("preview", "New session", activityMs = 20),
            SessionRow(
              "disk-1",
              "Effect-TS Grok Build VS Code Plugin Plan",
              activityMs = 10,
              lastTurn = Some("Continue the plan"),
            ),
            SessionRow("disk-2", "Ascent chat chrome", activityMs = 5, summary = Some("Composer and cards")),
          ),
        )
      case _ => ChatModel.empty
end PreviewScenes

object PreviewDiffs:
  val MainOld: String = "object Main\n"
  val MainNew: String = "object Main:\n  def run = ()\n"
  val MainPath        = "/tmp/Main.scala"
