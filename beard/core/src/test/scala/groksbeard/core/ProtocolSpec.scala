package groksbeard.core

import zio.json.*
import zio.test.*

object ProtocolSpec extends ZIOSpecDefault:
  def spec =
    suite("protocol")(
      test("HostMsg ready round-trips on _tag") {
        val json = HostMsg.Ready.toJson
        assertTrue(json.contains("\"_tag\":\"ready\""), json.fromJson[HostMsg] == Right(HostMsg.Ready))
      },
      test("HostMsg sessionMeta round-trips") {
        val msg  = HostMsg.SessionMeta("s1", "Grok's Beard", "normal")
        val json = msg.toJson
        assertTrue(json.fromJson[HostMsg] == Right(msg))
      },
      test("sessionMeta models round-trip") {
        val msg: HostMsg = HostMsg.SessionMeta(
          "s1",
          "Grok's Beard",
          "normal",
          modelId = "grok-4.6",
          availableModels = List(ModelOption("grok-4.6", "Grok 4.6")),
        )
        val set: WebviewMsg = WebviewMsg.SetModel("grok-4.6")
        assertTrue(msg.toJson.fromJson[HostMsg] == Right(msg), set.toJson.fromJson[WebviewMsg] == Right(set))
      },
      test("queued follow-ups round-trip") {
        val msg: HostMsg = HostMsg.Queued(List(QueuedPrompt("q1", "later")))
        assertTrue(msg.toJson.fromJson[HostMsg] == Right(msg))
      },
      test("sessionList and resumeSession round-trip") {
        val list: HostMsg =
          HostMsg.SessionList(
            List(SessionRow("s1", "Effect plan", activityMs = 9)),
            currentId = "s2",
            openPicker = true,
          )
        val resume: WebviewMsg = WebviewMsg.ResumeSession("s1")
        val neu: WebviewMsg    = WebviewMsg.NewSession
        assertTrue(
          list.toJson.fromJson[HostMsg] == Right(list),
          resume.toJson.fromJson[WebviewMsg] == Right(resume),
          neu.toJson.fromJson[WebviewMsg] == Right(neu),
        )
      },
      test("renameSession and deleteSession round-trip") {
        val rename: WebviewMsg = WebviewMsg.RenameSession("s1", "Plan")
        val auto: WebviewMsg   = WebviewMsg.RenameSession("s1", "", auto = true)
        val del: WebviewMsg    = WebviewMsg.DeleteSession("s1")
        assertTrue(
          rename.toJson.fromJson[WebviewMsg] == Right(rename),
          auto.toJson.fromJson[WebviewMsg] == Right(auto),
          del.toJson.fromJson[WebviewMsg] == Right(del),
        )
      },
      test("WebviewMsg send round-trips") {
        val msg = WebviewMsg.Send("hello")
        assertTrue(msg.toJson.fromJson[WebviewMsg] == Right(msg))
      },
      test("availableCommands and mentionResults round-trip") {
        val cmds: HostMsg  = HostMsg.AvailableCommands(List(SlashCommand("compact", "Compact context")))
        val files: HostMsg = HostMsg.MentionResults("src", List(MentionFile("src/Main.scala", "/src/Main.scala")))
        assertTrue(cmds.toJson.fromJson[HostMsg] == Right(cmds), files.toJson.fromJson[HostMsg] == Right(files))
      },
      test("setMode and mentionQuery round-trip") {
        val mode: WebviewMsg = WebviewMsg.SetMode("plan")
        val q: WebviewMsg    = WebviewMsg.MentionQuery("src")
        assertTrue(mode.toJson.fromJson[WebviewMsg] == Right(mode), q.toJson.fromJson[WebviewMsg] == Right(q))
      },
      test("composerChip and removeChip round-trip") {
        val chip: HostMsg =
          HostMsg.chip(PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50)))
        val drop: WebviewMsg = WebviewMsg.RemoveChip("/repo/src/Foo.scala", Some(10), Some(50))
        val add: WebviewMsg  = WebviewMsg.AddSelection
        assertTrue(
          chip.toJson.fromJson[HostMsg] == Right(chip),
          drop.toJson.fromJson[WebviewMsg] == Right(drop),
          add.toJson.fromJson[WebviewMsg] == Right(add),
        )
      },
      test("unknown HostMsg tag is a decode error") {
        val got = """{"_tag":"not-a-real-tag"}""".fromJson[HostMsg]
        assertTrue(got.isLeft)
      },
      test("permission card and plan verdict round-trip") {
        val perm: HostMsg = HostMsg.permission(
          PermissionCard(
            "r1",
            "tc",
            "Edit Foo.scala",
            List(PermissionOption("allow", "Allow", "allow_once")),
            hasDiff = true,
          )
        )
        val choice: WebviewMsg  = WebviewMsg.PermissionChoice("r1", "allow")
        val plan: HostMsg       = HostMsg.plan(PlanCard("p1", "# Plan\n\nDo it."))
        val verdict: WebviewMsg = WebviewMsg.PlanVerdict("p1", "approved")
        assertTrue(
          perm.toJson.fromJson[HostMsg] == Right(perm),
          choice.toJson.fromJson[WebviewMsg] == Right(choice),
          plan.toJson.fromJson[HostMsg] == Right(plan),
          verdict.toJson.fromJson[WebviewMsg] == Right(verdict),
        )
      },
      test("transcript snapshot round-trips") {
        val msg: HostMsg = HostMsg.Transcript(
          List(TurnView("t1", user = Some(TurnUser("hello from disk")), agent = "welcome back"))
        )
        assertTrue(msg.toJson.fromJson[HostMsg] == Right(msg))
      },
      test("agent chunks and turnEnd round-trip") {
        val chunk: HostMsg = HostMsg.AgentChunk("t1", "**hi**")
        val end: HostMsg   = HostMsg.TurnEnd("t1", "end_turn")
        assertTrue(chunk.toJson.fromJson[HostMsg] == Right(chunk), end.toJson.fromJson[HostMsg] == Right(end))
      },
      test("changes summary and keep/undo round-trip") {
        val summary: HostMsg = HostMsg.changes(
          ChangesSummary(
            1,
            3,
            1,
            List(ChangeFileView("/tmp/Main.scala", "modify", 3, 1, wholeFile = true)),
          )
        )
        val keep: WebviewMsg     = WebviewMsg.KeepChange("/tmp/Main.scala")
        val undo: WebviewMsg     = WebviewMsg.UndoChange("/tmp/Main.scala")
        val keepTurn: WebviewMsg = WebviewMsg.KeepTurn("t3")
        val undoAll: WebviewMsg  = WebviewMsg.UndoAll
        val diff: HostMsg        = HostMsg.DiffPreview("/tmp/Main.scala", "old", "new", wholeFile = true)
        assertTrue(
          summary.toJson.fromJson[HostMsg] == Right(summary),
          keep.toJson.fromJson[WebviewMsg] == Right(keep),
          undo.toJson.fromJson[WebviewMsg] == Right(undo),
          keepTurn.toJson.fromJson[WebviewMsg] == Right(keepTurn),
          undoAll.toJson.fromJson[WebviewMsg] == Right(undoAll),
          diff.toJson.fromJson[HostMsg] == Right(diff),
        )
      },
      test("live SSE payloads from grok agent stdio decode") {
        val user =
          """{"_tag":"userMessage","turnId":"turn_1","text":"hello","chips":[],"steer":false}"""
        val thought =
          """{"_tag":"thoughtChunk","turnId":"turn_1","text":"The"}"""
        val agent =
          """{"_tag":"agentChunk","turnId":"turn_1","text":"Hi"}"""
        val end =
          """{"_tag":"turnEnd","turnId":"turn_1","stopReason":"end_turn"}"""
        val commands = HostMsg.AvailableCommands(
          (1 to 40).toList.map(i => SlashCommand(s"skill-$i", "hint " * 40))
        )
        assertTrue(
          user.fromJson[HostMsg] == Right(HostMsg.UserMessage("turn_1", "hello")),
          thought.fromJson[HostMsg] == Right(HostMsg.ThoughtChunk("turn_1", "The")),
          agent.fromJson[HostMsg] == Right(HostMsg.AgentChunk("turn_1", "Hi")),
          end.fromJson[HostMsg] == Right(HostMsg.TurnEnd("turn_1", "end_turn")),
          commands.toJson.fromJson[HostMsg] == Right(commands),
        )
      },
      test("slash commands with ACP extra input still decode") {
        val json =
          """{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"compact","description":"Compact context","input":{"hint":"optional"}}]}"""
        val got = json.fromJson[AcpUpdate]
        assertTrue(got == Right(AcpUpdate.Commands(List(SlashCommand("compact", "Compact context")))))
      },
    )
end ProtocolSpec
