package groksbeard.core

import ascent.squawk.Eq
import zio.json.JsonCodec
import zio.json.ast.Json

final case class QuestionAnswer(
    questionId: String,
    optionIds: List[String] = Nil,
    freeText: Option[String] = None,
) derives JsonCodec

/** UI fold of an ask-user-question card: current item, selected ids, free text. */
final case class QuestionDraft(
    requestId: RequestId = RequestId.empty,
    index: Int = 0,
    selected: Map[String, List[String]] = Map.empty,
    freeText: Map[String, String] = Map.empty,
) derives Eq

object QuestionDraft:
  val empty: QuestionDraft = QuestionDraft()

  def align(card: QuestionCard, draft: QuestionDraft): QuestionDraft =
    if draft.requestId == card.requestId then
      val last = math.max(0, card.questions.size - 1)
      draft.copy(index = draft.index.max(0).min(last))
    else QuestionDraft(requestId = card.requestId)

  def current(card: QuestionCard, draft: QuestionDraft): Option[AgentQuestion] =
    card.questions.lift(align(card, draft).index)

  def prev(card: QuestionCard, draft: QuestionDraft): QuestionDraft =
    val d = align(card, draft)
    d.copy(index = (d.index - 1).max(0))

  def next(card: QuestionCard, draft: QuestionDraft): QuestionDraft =
    val d    = align(card, draft)
    val last = math.max(0, card.questions.size - 1)
    d.copy(index = (d.index + 1).min(last))

  def isLast(card: QuestionCard, draft: QuestionDraft): Boolean =
    val d = align(card, draft)
    d.index >= math.max(0, card.questions.size - 1)

  def pick(card: QuestionCard, draft: QuestionDraft, optionId: String): QuestionDraft =
    current(card, draft) match
      case None    => align(card, draft)
      case Some(q) =>
        val d   = align(card, draft)
        val cur = d.selected.getOrElse(q.id, Nil)
        val ids =
          if q.allowMultiple then if cur.contains(optionId) then cur.filterNot(_ == optionId) else cur :+ optionId
          else List(optionId)
        d.copy(selected = d.selected.updated(q.id, ids))

  def setFreeText(card: QuestionCard, draft: QuestionDraft, text: String): QuestionDraft =
    current(card, draft) match
      case None    => align(card, draft)
      case Some(q) =>
        val d = align(card, draft)
        d.copy(freeText = d.freeText.updated(q.id, text))

  def answers(card: QuestionCard, draft: QuestionDraft): List[QuestionAnswer] =
    val d = align(card, draft)
    card.questions.map { q =>
      val ft = d.freeText.get(q.id).map(_.trim).filter(_.nonEmpty)
      QuestionAnswer(q.id, d.selected.getOrElse(q.id, Nil), ft)
    }

  /** Grok ACP `AskUserQuestionExtResponse.Accepted`. Keys are question ids (prompt text on the live wire). */
  def acceptedJson(answers: List[QuestionAnswer]): Json =
    val rows = answers.flatMap { a =>
      val ft  = a.freeText.map(_.trim).filter(_.nonEmpty)
      val ids = a.optionIds.filter(_.nonEmpty)
      val vec = if ids.nonEmpty then ids else if ft.nonEmpty then List("Other") else Nil
      if vec.isEmpty || a.questionId.isEmpty then None
      else Some(a.questionId -> vec)
    }
    val answersObj = Json.Obj(rows.map((k, v) => k -> Json.Arr(v.map(Json.Str(_))*))*)
    val notes      =
      answers.flatMap { a =>
        a.freeText.map(_.trim).filter(_.nonEmpty).map { n =>
          a.questionId -> Json.Obj("notes" -> Json.Str(n))
        }
      }
    val fields =
      List("outcome" -> Json.Str("accepted"), "answers" -> answersObj) ++
        (if notes.isEmpty then Nil else List("annotations" -> Json.Obj(notes*)))
    Json.Obj(fields*)
  end acceptedJson

  def cancelledJson: Json = Json.Obj("outcome" -> Json.Str("cancelled"))

  def optionCaption(idx: Int, opt: QuestionOption): String =
    val extra = opt.description.trim
    if extra.nonEmpty && !extra.equalsIgnoreCase(opt.label) then s"${idx + 1} ${opt.label}  $extra"
    else s"${idx + 1} ${opt.label}"

  def optionKey(key: String, q: AgentQuestion): Option[String] =
    val idx =
      if key.length == 1 && key(0) >= '1' && key(0) <= '9' then Some(key(0) - '1')
      else if key.length == 1 && key(0) >= 'a' && key(0) <= 'f' then Some(9 + (key(0) - 'a'))
      else None
    idx.flatMap(q.options.lift).map(_.id)

  def navKey(key: String): Option[String] =
    key match
      case "ArrowLeft" | "h" | "["  => Some("prev")
      case "ArrowRight" | "l" | "]" => Some("next")
      case _                        => None
end QuestionDraft
