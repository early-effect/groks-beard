package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object QuestionDraftSpec extends ZIOSpecDefault:
  private val style =
    AgentQuestion(
      "style",
      "How should the transcript look?",
      List(QuestionOption("dense", "Dense"), QuestionOption("roomy", "Roomy")),
    )
  private val extras =
    AgentQuestion(
      "extras",
      "Which extras?",
      List(QuestionOption("a", "A"), QuestionOption("b", "B"), QuestionOption("c", "C")),
      allowMultiple = true,
    )
  private val note =
    AgentQuestion("note", "Anything else?", Nil, allowFreeText = true)
  private val card = QuestionCard("q-1", List(style, extras, note))

  def spec =
    suite("QuestionDraft")(
      test("align resets when the request id changes") {
        val held = QuestionDraft("old", index = 2)
        val next = QuestionDraft.align(card, held)
        assertTrue(next.requestId == "q-1", next.index == 0)
      },
      test("prev and next clamp") {
        val start = QuestionDraft.align(card, QuestionDraft.empty)
        val last  = QuestionDraft.next(card, QuestionDraft.next(card, QuestionDraft.next(card, start)))
        val back  = QuestionDraft.prev(card, last)
        assertTrue(
          QuestionDraft.current(card, start).exists(_.id == "style"),
          QuestionDraft.isLast(card, last),
          QuestionDraft.current(card, last).exists(_.id == "note"),
          back.index == 1,
        )
      },
      test("single pick replaces, multi pick toggles") {
        val d0 = QuestionDraft.align(card, QuestionDraft.empty)
        val d1 = QuestionDraft.pick(card, d0, "dense")
        val d2 = QuestionDraft.pick(card, d1, "roomy")
        val d3 = QuestionDraft.next(card, d2)
        val d4 = QuestionDraft.pick(card, d3, "a")
        val d5 = QuestionDraft.pick(card, d4, "b")
        val d6 = QuestionDraft.pick(card, d5, "a")
        assertTrue(
          d2.selected("style") == List("roomy"),
          d6.selected("extras") == List("b"),
        )
      },
      test("answers include free text and option ids") {
        val d0 = QuestionDraft.align(card, QuestionDraft.empty)
        val d1 = QuestionDraft.pick(card, d0, "dense")
        val d2 = QuestionDraft.next(card, QuestionDraft.next(card, d1))
        val d3 = QuestionDraft.setFreeText(card, d2, "  hello  ")
        val as = QuestionDraft.answers(card, d3)
        assertTrue(
          as.exists(a => a.questionId == "style" && a.optionIds == List("dense")),
          as.exists(a => a.questionId == "note" && a.freeText.contains("hello")),
        )
      },
      test("optionKey maps 1-9 and a-f on the current question") {
        val opts = (1 to 15).map(i => QuestionOption(s"o$i", s"O$i")).toList
        val big  = AgentQuestion("big", "Pick", opts)
        assertTrue(
          QuestionDraft.optionKey("1", style).contains("dense"),
          QuestionDraft.optionKey("2", extras).contains("b"),
          QuestionDraft.optionKey("a", extras).isEmpty,
          QuestionDraft.optionKey("3", extras).contains("c"),
          QuestionDraft.optionKey("9", big).contains("o9"),
          QuestionDraft.optionKey("a", big).contains("o10"),
          QuestionDraft.optionKey("f", big).contains("o15"),
        )
      },
      test("fromAcp reads Grok's question/label/multiSelect wire") {
        val raw = Json.Obj(
          "question"    -> Json.Str("Which color?"),
          "multiSelect" -> Json.Bool(false),
          "options"     -> Json.Arr(
            Json.Obj("label" -> Json.Str("Red"), "description" -> Json.Str("warm")),
            Json.Obj("label" -> Json.Str("Blue")),
          ),
        )
        val q = AgentQuestion.fromAcp(raw)
        assertTrue(
          q.exists(_.prompt == "Which color?"),
          q.exists(_.id == "Which color?"),
          q.exists(_.options.map(_.label) == List("Red", "Blue")),
          q.exists(_.options.headOption.exists(_.description == "warm")),
          q.exists(!_.allowMultiple),
          q.exists(_.allowFreeText),
        )
      },
      test("optionCaption skips a description that repeats the label") {
        assertTrue(
          QuestionDraft.optionCaption(0, QuestionOption("Red", "Red", "Red")) == "1 Red",
          QuestionDraft.optionCaption(1, QuestionOption("Blue", "Blue", "cool")) == "2 Blue  cool",
        )
      },
      test("acceptedJson keys answers by question id and tags outcome") {
        val json = QuestionDraft.acceptedJson(
          List(QuestionAnswer("Which color?", List("Red"), None), QuestionAnswer("note", Nil, Some("hi")))
        )
        val blob = json.toString
        assertTrue(
          blob.contains("accepted"),
          blob.contains("Which color?"),
          blob.contains("Red"),
          blob.contains("Other"),
          blob.contains("hi"),
        )
      },
    )
end QuestionDraftSpec
