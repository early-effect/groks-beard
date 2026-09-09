package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class WorkflowRun(
    name: String,
    phase: String = "",
    status: String = "",
    detail: String = "",
    paused: Boolean = false,
) derives JsonCodec

object WorkflowRuns:
  val EmptyNotice: String = "No workflow runs in this session"

  def fold(params: Json, current: List[WorkflowRun]): Option[List[WorkflowRun]] =
    obj(params).flatMap { o =>
      str(o, "sessionUpdate") match
        case Some("workflow_updated") | Some("workflow_run") =>
          from(o).map(upsert(current, _))
        case Some("workflow_stopped") | Some("workflow_deleted") =>
          str(o, "name").orElse(str(o, "run")).map(n => current.filterNot(_.name == n))
        case _ => None
    }

  def upsert(rows: List[WorkflowRun], row: WorkflowRun): List[WorkflowRun] =
    if row.name.isEmpty then rows
    else if rows.exists(_.name == row.name) then rows.map(r => if r.name == row.name then row else r)
    else rows :+ row

  def headline(rows: List[WorkflowRun]): String =
    val live = rows.count(r => r.status != "completed" && r.status != "stopped" && r.status != "failed")
    if rows.isEmpty then "Workflow runs"
    else if live == 0 then s"Workflow runs ${rows.size}"
    else s"Workflow runs $live live"

  private def from(obj: Json.Obj): Option[WorkflowRun] =
    val name = str(obj, "name").orElse(str(obj, "run")).orElse(str(obj, "display_name")).getOrElse("")
    if name.isEmpty then None
    else
      Some(
        WorkflowRun(
          name = name,
          phase = str(obj, "phase").getOrElse(""),
          status = str(obj, "status").orElse(str(obj, "result")).getOrElse("running"),
          detail = str(obj, "detail").orElse(str(obj, "progress")).getOrElse(""),
          paused = str(obj, "status").contains("paused"),
        )
      )
    end if
  end from

  private def obj(params: Json): Option[Json.Obj] =
    params match
      case o: Json.Obj =>
        o.fields.collectFirst { case ("update", inner: Json.Obj) => inner }.orElse(Some(o))
      case _ => None

  private def str(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (k, Json.Str(s)) if k == key && s.nonEmpty => s }
end WorkflowRuns
