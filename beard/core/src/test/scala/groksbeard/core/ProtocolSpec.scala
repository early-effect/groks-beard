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
      test("unknown HostMsg tag is a decode error") {
        val got = """{"_tag":"not-a-real-tag"}""".fromJson[HostMsg]
        assertTrue(got.isLeft)
      },
      test("permission card and plan verdict round-trip") {
        val perm: HostMsg = HostMsg.Permission(
          PermissionCard(
            "r1",
            "tc",
            "Edit Foo.scala",
            List(PermissionOption("allow", "Allow", "allow_once")),
            hasDiff = true,
          )
        )
        val choice: WebviewMsg  = WebviewMsg.PermissionChoice("r1", "allow")
        val plan: HostMsg       = HostMsg.Plan(PlanCard("p1", "# Plan\n\nDo it."))
        val verdict: WebviewMsg = WebviewMsg.PlanVerdict("p1", "approved")
        assertTrue(
          perm.toJson.fromJson[HostMsg] == Right(perm),
          choice.toJson.fromJson[WebviewMsg] == Right(choice),
          plan.toJson.fromJson[HostMsg] == Right(plan),
          verdict.toJson.fromJson[WebviewMsg] == Right(verdict),
        )
      },
      test("agent chunks and turnEnd round-trip") {
        val chunk: HostMsg = HostMsg.AgentChunk("t1", "**hi**")
        val end: HostMsg   = HostMsg.TurnEnd("t1", "end_turn")
        assertTrue(chunk.toJson.fromJson[HostMsg] == Right(chunk), end.toJson.fromJson[HostMsg] == Right(end))
      },
    )
end ProtocolSpec
