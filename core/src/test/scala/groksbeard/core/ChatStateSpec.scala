package groksbeard.core

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
      test("withConfig copies model and effort from options") {
        val opts = List(
          ConfigOption(ConfigOption.ModelKey, currentValue = Some("grok-4.6")),
          ConfigOption(ConfigOption.EffortKey, currentValue = Some("high")),
        )
        val next = ChatState.seed(".", SettingsState.defaults, Nil).withConfig(opts)
        assertTrue(next.modelId.value == "grok-4.6", next.effort == "high", next.configOptions == opts)
      },
    )
end ChatStateSpec
