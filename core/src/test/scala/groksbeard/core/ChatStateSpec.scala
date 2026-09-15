package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object ChatStateSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatState")(
      test("takeRpc assigns sequential ids and records the method") {
        val s0        = ChatState.seed(".", SettingsState.defaults, ChatRuntime.DefaultModes)
        val (id1, s1) = s0.takeRpc(AcpMethod.Initialize, None)
        val (id2, s2) = s1.takeRpc(AcpMethod.SessionNew, Some(SessionId("sess")))
        assertTrue(
          id1 == RpcId.Num(1),
          id2 == RpcId.Num(2),
          s2.pendingMethod.get(id1).contains(AcpMethod.Initialize),
          s2.pendingMethod.get(id2).contains(AcpMethod.SessionNew),
          s2.pendingLoad.get(id2).contains(SessionId("sess")),
        )
      },
      test("enqueue then dequeue is FIFO and clears chips") {
        val chip    = PromptChip("Main.scala", "/tmp/Main.scala", ChipSource.Mention)
        val s0      = ChatState.seed(".", SettingsState.defaults, Nil).copy(chips = List(chip))
        val s1      = s0.enqueue("one", List(chip), Nil).enqueue("two", Nil, Nil)
        val (a, s2) = s1.dequeue
        val (b, s3) = s2.dequeue
        val (c, _)  = s3.dequeue
        assertTrue(
          a.exists(_.text == "one"),
          b.exists(_.text == "two"),
          c.isEmpty,
          s1.chips.isEmpty,
        )
      },
      test("setParams sends an ACP id value, not a nested object") {
        val json = ConfigOption.setParams(SessionId("sess"), ConfigOption.EffortKey, "low")
        assertTrue(
          json == Json.Obj(
            "sessionId" -> Json.Str("sess"),
            "configId"  -> Json.Str("reasoning_effort"),
            "type"      -> Json.Str("id"),
            "value"     -> Json.Str("low"),
          )
        )
      },
      test("withConfig copies model and effort from options") {
        val opts = List(
          ConfigOption(ConfigOption.ModelKey, currentValue = Some("grok-4.6")),
          ConfigOption(ConfigOption.EffortKey, currentValue = Some("high")),
        )
        val next = ChatState.seed(".", SettingsState.defaults, Nil).withConfig(opts)
        assertTrue(next.modelId.value == "grok-4.6", next.effort == "high", next.configOptions == opts)
      },
      test("withConfig keeps model capability meta from the live catalog") {
        val seeded =
          ChatState
            .seed(".", SettingsState.defaults, Nil)
            .copy(
              modelId = ModelId("grok-4.6"),
              models = List(ModelOption(ModelId("grok-4.6"), "Grok 4.6", _meta = Some(Effort.grokMeta()))),
            )
        val opts = List(
          ConfigOption(
            ConfigOption.ModelKey,
            currentValue = Some("grok-4.6"),
            options = List(ConfigSelect("grok-4.6", Some("Grok 4.6"))),
          )
        )
        val next = seeded.withConfig(opts)
        assertTrue(
          next.models.headOption.flatMap(_._meta).isDefined,
          Effort.of(next.currentModel).nonEmpty,
        )
      },
      test("adoptTurns continues turnSeq past the snapshot") {
        val snap = List(
          TurnView("turn_1", user = Some(TurnUser("old")), agent = "done", stopReason = Some(StopReason.EndTurn)),
          TurnView("turn_2", user = Some(TurnUser("later")), agent = "ok", stopReason = Some(StopReason.EndTurn)),
        )
        val s0 = ChatState.seed(".", SettingsState.defaults, Nil).beginResume(SessionId("sess")).adoptTurns(snap)
        val s1 = s0.startTurn("hello", Nil, SessionId("sess"))
        assertTrue(
          s0.turnSeq == 2,
          s0.currentTurn == TurnId("turn_2"),
          !s0.running,
          s1.currentTurn == TurnId("turn_3"),
          s1.turnSeq == 3,
        )
      },
      test("beginResume then onDisk(true) paints without loading chrome") {
        val s0 = ChatState.seed(".", SettingsState.defaults, Nil).beginResume(SessionId("sess"))
        val s1 = s0.onDisk(true)
        assertTrue(
          s0.phase == SessionPhase.ResumeDisk(SessionId("sess")),
          s0.loading,
          s1.phase == SessionPhase.ResumePainted(SessionId("sess")),
          s1.diskPainted,
          !s1.loading,
          s1.metaLoading,
        )
      },
      test("resetLocal and beginNew are Empty, not a resume wait") {
        val s =
          ChatState.seed(".", SettingsState.defaults, Nil).beginResume(SessionId("sess")).resetLocal.readyEmpty
        assertTrue(s.phase == SessionPhase.Empty, !s.loading, s.pendingResume.isEmpty)
      },
      test("finishAttach is Live") {
        val s =
          ChatState.seed(".", SettingsState.defaults, Nil).beginResume(SessionId("sess")).onDisk(true).finishAttach
        assertTrue(
          s.phase == SessionPhase.Live(SessionId("sess")),
          s.pendingResume.isEmpty,
          !s.metaLoading,
        )
      },
      test("staleResume ignores a cancelled or switched attach") {
        val seed      = ChatState.seed(".", SettingsState.defaults, Nil)
        val waiting   = seed.beginResume(SessionId("disk"))
        val cancelled = waiting.cancelPendingResume.readyEmpty.copy(sessionId = Some(SessionId("neu")))
        val switched  = waiting.cancelPendingResume.beginResume(SessionId("live"))
        val live      = seed.readyEmpty.copy(sessionId = Some(SessionId("sess")))
        assertTrue(
          cancelled.staleResume(Some(SessionId("disk"))),
          switched.staleResume(Some(SessionId("disk"))),
          !switched.staleResume(Some(SessionId("live"))),
          !live.staleResume(Some(SessionId("sess"))),
          live.staleResume(Some(SessionId("other"))),
        )
      },
    )
end ChatStateSpec
