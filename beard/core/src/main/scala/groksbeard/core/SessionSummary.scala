package groksbeard.core

import zio.json.*

final case class SessionInfo(id: String, cwd: String) derives JsonCodec

final case class SessionSummary(
    info: SessionInfo,
    session_summary: Option[String] = None,
    generated_title: Option[String] = None,
    title_is_manual: Option[Boolean] = None,
    created_at: Option[String] = None,
    updated_at: Option[String] = None,
    last_active_at: Option[String] = None,
    num_messages: Option[Int] = None,
    num_chat_messages: Option[Int] = None,
    current_model_id: Option[String] = None,
    parent_session_id: Option[String] = None,
    agent_name: Option[String] = None,
    last_turn_summary: Option[String] = None,
    last_recap: Option[String] = None,
    sandbox_profile: Option[String] = None,
    reasoning_effort: Option[String] = None,
    grok_home: Option[String] = None,
) derives JsonCodec

object SessionSummary:
  def decode(text: String): Option[SessionSummary] =
    text.fromJson[SessionSummary].toOption

  def title(summary: SessionSummary): String =
    val manual = summary.session_summary.filter(_ => summary.title_is_manual.contains(true))
    manual
      .orElse(summary.generated_title)
      .orElse(summary.session_summary)
      .filter(_.nonEmpty)
      .getOrElse(summary.info.id)

  def row(id: String, activityMs: Long, summary: Option[SessionSummary]): SessionRow =
    summary match
      case None    => SessionRow(id, id, activityMs)
      case Some(s) =>
        SessionRow(
          id = id,
          title = title(s),
          activityMs = activityMs,
          summary = s.session_summary.orElse(s.last_recap).filter(_.nonEmpty),
          lastTurn = s.last_turn_summary.filter(_.nonEmpty),
          modelId = s.current_model_id.filter(_.nonEmpty),
          messages = s.num_chat_messages.orElse(s.num_messages),
        )
end SessionSummary
