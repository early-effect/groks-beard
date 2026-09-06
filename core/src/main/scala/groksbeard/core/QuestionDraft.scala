package groksbeard.core

import zio.json.JsonCodec

final case class QuestionAnswer(
    questionId: String,
    optionIds: List[String] = Nil,
    freeText: Option[String] = None,
) derives JsonCodec

/** UI fold of an ask-user-question card: current item, selected ids, free text. */
final case class QuestionDraft(
    requestId: String = "",
    index: Int = 0,
    selected: Map[String, List[String]] = Map.empty,
    freeText: Map[String, String] = Map.empty,
)

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
