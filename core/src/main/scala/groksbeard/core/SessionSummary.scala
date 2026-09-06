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
    val named  =
      List(manual, summary.generated_title, summary.session_summary, summary.last_turn_summary).flatten
        .map(_.trim)
        .find(t => t.nonEmpty && !SessionIndex.isOpaqueId(t, summary.info.id))
    named.getOrElse("Untitled session")

  def epochMs(iso: String): Option[Long] =
    try Some(java.time.Instant.parse(iso.trim).toEpochMilli)
    catch case _: Exception => None

  /** Last time the user used the session. Ignores metadata-only rewrites of updated_at. */
  def lastUsedMs(summary: SessionSummary): Option[Long] =
    summary.last_active_at.flatMap(epochMs)

  def activityMs(summary: Option[SessionSummary], conversationMtimeMs: Option[Long], summaryMtimeMs: Long): Long =
    val lastActive = summary.flatMap(lastUsedMs)
    (lastActive.toList ++ conversationMtimeMs.toList).maxOption
      .orElse(summary.flatMap(_.updated_at).flatMap(epochMs))
      .orElse(summary.flatMap(_.created_at).flatMap(epochMs))
      .getOrElse(summaryMtimeMs)

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
          messages = s.num_messages,
        )
end SessionSummary
