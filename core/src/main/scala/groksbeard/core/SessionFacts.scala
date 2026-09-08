package groksbeard.core

import ascent.squawk.Eq

enum SessionPane:
  case Info, Context

object SessionPane:
  given Eq[SessionPane] = Eq.derived

  def other(pane: SessionPane): SessionPane =
    pane match
      case Info    => Context
      case Context => Info

  def title(pane: SessionPane): String =
    pane match
      case Info    => "Session"
      case Context => "Context"

  def hint(pane: SessionPane): String =
    pane match
      case Info    => "c copies the session ID · y copies all"
      case Context => "y copies all"
end SessionPane

final case class FactRow(id: String, label: String, value: String, copy: String)

object SessionFacts:
  def rows(pane: SessionPane, model: ChatModel): List[FactRow] =
    pane match
      case SessionPane.Info    => info(model)
      case SessionPane.Context => context(model)

  def block(pane: SessionPane, model: ChatModel): String =
    val head = SessionPane.title(pane)
    val body = rows(pane, model).map(r => s"${r.label}: ${r.copy}").mkString("\n")
    s"$head\n$body"

  def sessionId(model: ChatModel): Option[String] =
    Option(model.sessionId.value).map(_.trim).filter(_.nonEmpty)

  def occupancy(model: ChatModel): Option[Occupancy] =
    model.occupancy.filter(_.size > 0)

  def title(model: ChatModel): String =
    val fromRow   = model.sessions.find(_.id == model.sessionId).map(SessionIndex.displayTitle)
    val fromTitle =
      Option(model.title).map(_.trim).filter { t =>
        t.nonEmpty && t != "Grok's Beard" && !SessionIndex.isOpaqueId(t, model.sessionId)
      }
    fromRow
      .filter(_ != "Untitled session")
      .orElse(fromTitle)
      .filter(_.nonEmpty)
      .getOrElse {
        if model.sessionId.nonEmpty || model.inSession || model.turns.nonEmpty then "This session"
        else "No session"
      }
  end title

  def info(model: ChatModel): List[FactRow] =
    val named = title(model)
    List(
      Some(FactRow("title", "Title", named, named)),
      sessionId(model).map(id => FactRow("session", "Session", id, id)),
      directory(model),
      Some(modelRow(model)),
      Some(modeRow(model)),
      Some(turnsRow(model)),
      occupancyLabel(model),
      mcpRow(model),
    ).flatten
  end info

  def context(model: ChatModel): List[FactRow] =
    occupancyBreakdown(model) ++ List(Some(turnsRow(model)), mcpRow(model)).flatten

  def modelLine(model: ChatModel): String =
    if model.modelId.isEmpty && model.models.isEmpty then "—"
    else Effort.chip(ModelOption.label(model.modelId, model.models), model.effort)

  def turns(model: ChatModel): Int = model.turns.size

  private def directory(model: ChatModel): Option[FactRow] =
    Option(model.cwd).map(_.trim).filter(_.nonEmpty).map(d => FactRow("directory", "Directory", d, d))

  private def modelRow(model: ChatModel): FactRow =
    val line = modelLine(model)
    FactRow("model", "Model", line, line)

  private def modeRow(model: ChatModel): FactRow =
    val line = ModeLabel.modeLabel(model.modeId, model.modes)
    FactRow("mode", "Mode", line, line)

  private def turnsRow(model: ChatModel): FactRow =
    val n = turns(model).toString
    FactRow("turns", "Turns", n, n)

  private def occupancyLabel(model: ChatModel): Option[FactRow] =
    occupancy(model).map { o =>
      val line = Occupancy.label(o.used, o.size)
      FactRow("context", "Context", line, line)
    }

  private def occupancyBreakdown(model: ChatModel): List[FactRow] =
    occupancy(model) match
      case None =>
        val note = "Grok has not reported context usage yet"
        List(FactRow("usage", "Usage", note, note))
      case Some(o) =>
        val used = Occupancy.compact(o.used)
        val free = Occupancy.free(o.used, o.size)
        val left = s"${Occupancy.compact(free)} · ${Occupancy.percent(free, o.size)}%"
        List(
          FactRow("used", "Used", used, used),
          FactRow("free", "Free", left, left),
          FactRow("window", "Window", Occupancy.compact(o.size), Occupancy.compact(o.size)),
        )

  private def mcpRow(model: ChatModel): Option[FactRow] =
    if model.mcps.isEmpty then None
    else
      val line = model.mcps
        .map(r => if r.enabled then r.name else s"${r.name} (off)")
        .mkString(", ")
      Some(FactRow("mcp", "MCP", line, line))
end SessionFacts
