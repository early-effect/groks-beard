package groksbeard.core

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object EffortSpec extends ZIOSpecDefault:
  private val high = EffortLevel("high", default = Some(true))
  private val low  = EffortLevel("low")
  private val meta = Json.Obj(
    "reasoningEfforts" -> Json.Arr(
      Json.Obj("value" -> Json.Str("low")),
      Json.Obj("value" -> Json.Str("high"), "default" -> Json.Bool(true)),
      Json.Obj("value" -> Json.Str("xhigh")),
    ),
    "reasoningEffort" -> Json.Str("high"),
  )
  private val grok = ModelOption("grok-4.6", "Grok 4.6", _meta = Some(meta))
  private val rx   = ModelOption("rx", "Reasoning X", _meta = Some(meta))
  private val fast = ModelOption("grok-code-fast-1", "Grok Code Fast")

  def spec =
    suite("Effort")(
      test("of reads reasoningEfforts from model _meta") {
        val levels = Effort.of(Some(grok)).map(_.value)
        assertTrue(levels == List("low", "high", "xhigh"), Effort.defaultOf(Some(grok)) == "high")
      },
      test("activeOf prefers the stamped reasoningEffort") {
        val stamped = grok.copy(_meta = Some(Effort.grokMeta("xhigh")))
        assertTrue(Effort.activeOf(Some(stamped)) == "xhigh", Effort.activeOf(Some(fast)).isEmpty)
      },
      test("pick matches aliases and values") {
        val allowed = List(low, high, EffortLevel("xhigh"))
        assertTrue(
          Effort.pick("HIGH", allowed).map(_.value).contains("high"),
          Effort.pick("extra high", allowed).map(_.value).contains("xhigh"),
          Effort.pick("nope", allowed).isEmpty,
          Effort.parse("").isLeft,
        )
      },
      test("unknown copy lists allowed levels") {
        assertTrue(
          Effort.unknown("deep", List(low, high)).contains("low, high"),
          Effort.unknown("high", Nil).contains("does not support"),
        )
      },
      test("chip appends effort when set") {
        assertTrue(Effort.chip("Grok 4.6", "high") == "Grok 4.6 · high", Effort.chip("Grok 4.6", "") == "Grok 4.6")
      },
      test("live grok-4.6 _meta still decodes with extra keys") {
        val json =
          """{"modelId":"grok-4.6","name":"Grok 4.6","description":"SpaceXAI's latest frontier model","_meta":{"totalContextTokens":500000,"agentType":"grok-build-plan","supportsReasoningEffort":true,"reasoningEffort":"high","reasoningEfforts":[{"id":"xhigh","value":"xhigh","label":"Extra High Effort","description":"Highest effort and reasoning level","default":false},{"id":"high","value":"high","label":"High Effort","default":true}]}}"""
        val got = json.fromJson[ModelOption].toOption
        val res =
          """{"sessionId":"s","models":{"currentModelId":"grok-4.6","availableModels":[]},"_meta":{"ok":true}}"""
        assertTrue(
          got.exists(_.modelId == "grok-4.6"),
          Effort.of(got).map(_.value) == List("xhigh", "high"),
          Effort.activeOf(got) == "high",
          Effort.of(got).headOption.flatMap(_.label).contains("Extra High Effort"),
          res.fromJson[SessionNewResult].isRight,
        )
      },
      test("splitModelArgs peels a trailing effort") {
        val models = List(grok, rx, fast)
        assertTrue(
          Effort.splitModelArgs("Grok 4.6", models).map(p => p._1.modelId -> p._2) == Right("grok-4.6" -> None),
          Effort.splitModelArgs("Grok 4.6 high", models).map(p => p._1.modelId -> p._2) ==
            Right("grok-4.6" -> Some("high")),
          Effort.splitModelArgs("Reasoning X extra high", models).map(p => p._1.modelId -> p._2) ==
            Right("rx" -> Some("xhigh")),
          Effort.splitModelArgs("nope", models).isLeft,
          Effort.splitModelArgs("Grok Code Fast high", models).isLeft,
        )
      },
    )
end EffortSpec
