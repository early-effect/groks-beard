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
  val Missing: String     = "Workflow pause/resume/stop isn't available."
  val Verbs: Set[String]  = Set("pause", "resume", "stop")

  def verbKey(key: String): Option[String] =
    key match
      case "p" | "P" => Some("pause")
      case "r" | "R" => Some("resume")
      case "x" | "X" => Some("stop")
      case _         => None

  def isNav(key: String): Boolean =
    key == "ArrowDown" || key == "ArrowUp" || key == "Home" || key == "End" || verbKey(key).nonEmpty

  def command(verb: String, name: String): String =
    s"/workflow $verb $name"

  def offers(commands: List[SlashCommand]): Boolean =
    commands.exists(c => c.name.stripPrefix("/").equalsIgnoreCase("workflow"))

  def parseManage(args: String): Option[(String, String)] =
    val t = args.trim
    val i = t.indexWhere(_.isWhitespace)
    if i < 0 then None
    else
      val verb = t.take(i).toLowerCase
      val name = t.drop(i).trim
      if Verbs.contains(verb) && name.nonEmpty then Some((verb, name)) else None

  def clamp(runs: List[WorkflowRun], sel: Option[String]): Option[String] =
    if runs.isEmpty then None
    else if sel.exists(n => runs.exists(_.name == n)) then sel
    else runs.headOption.map(_.name)

  def step(runs: List[WorkflowRun], sel: Option[String], key: String): Option[String] =
    val names = runs.map(_.name)
    if names.isEmpty then None
    else
      val cur = clamp(runs, sel).map(names.indexOf).filter(_ >= 0).getOrElse(0)
      key match
        case "ArrowDown" => Some(names((cur + 1) % names.size))
        case "ArrowUp"   => Some(names((cur - 1 + names.size) % names.size))
        case "Home"      => names.headOption
        case "End"       => names.lastOption
        case _           => clamp(runs, sel)
  end step

  def applyVerb(runs: List[WorkflowRun], name: String, verb: String): List[WorkflowRun] =
    runs.map { r =>
      if r.name != name then r
      else
        verb match
          case "pause"  => r.copy(status = "paused", paused = true)
          case "resume" => r.copy(status = "running", paused = false)
          case "stop"   => r.copy(status = "stopped", paused = false)
          case _        => r
    }

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
