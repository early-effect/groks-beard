package groksbeard.core

import ascent.squawk.Eq
import zio.*
import zio.json.*
import zio.json.ast.Json

final case class TaskRow(
    id: TaskId,
    kind: TaskKind,
    status: TaskStatus,
    label: String,
    detail: String = "",
    owned: Boolean = false,
) derives JsonCodec,
      Eq

final case class LoopSpec(interval: Duration, prompt: String):
  def human: String = Tasks.humanInterval(interval)

object Tasks:
  val LoopUsage: String = "Usage: /loop 5m <prompt>"

  val MinLoop: Duration = 60.seconds

  def upsert(rows: List[TaskRow], row: TaskRow): List[TaskRow] =
    if row.id.isEmpty || row.label.isEmpty then rows
    else if rows.exists(_.id == row.id) then rows.map(r => if r.id == row.id then merge(r, row) else r)
    else rows :+ row

  def remove(rows: List[TaskRow], id: TaskId): List[TaskRow] =
    rows.filterNot(_.id == id)

  def running(rows: List[TaskRow]): List[TaskRow] =
    rows.filter(r => TaskStatus.isLive(r.status))

  def grouped(rows: List[TaskRow]): List[(Option[String], List[TaskRow])] =
    val subs = rows.filter(_.kind == TaskKind.Subagent)
    val rest = rows.filterNot(_.kind == TaskKind.Subagent)
    val head = if subs.isEmpty then Nil else List(Some("Subagents") -> subs)
    val tail = if rest.isEmpty then Nil else List(None -> rest)
    head ++ tail

  def lifecycle(row: TaskRow): String =
    val quoted = "\"" + clip(row.label, 60) + "\""
    val extra  = if row.detail.nonEmpty then s" (${row.detail})" else ""
    val word   =
      row.status match
        case TaskStatus.Running   => "running"
        case TaskStatus.Completed => "completed"
        case TaskStatus.Failed    => "failed"
        case TaskStatus.Cancelled => "cancelled"
    s"Subagent $word: $quoted$extra"
  end lifecycle

  def headline(rows: List[TaskRow]): String =
    val live = running(rows).size
    if rows.isEmpty then "Tasks"
    else if live == 0 then s"Tasks ${rows.size}"
    else s"Tasks $live running"

  def statusLine(rows: List[TaskRow]): String =
    val live = running(rows)
    if live.isEmpty then ""
    else
      val parts =
        List(
          count(live, TaskKind.Command, "command", "commands"),
          count(live, TaskKind.Monitor, "monitor", "monitors"),
          count(live, TaskKind.Loop, "loop", "loops"),
          count(live, TaskKind.Subagent, "subagent", "subagents"),
        ).flatten
      if parts.isEmpty then ""
      else parts.mkString(" · ") + " still running"
    end if
  end statusLine

  def mark(status: TaskStatus): String =
    status match
      case TaskStatus.Running   => "◎"
      case TaskStatus.Completed => "✓"
      case TaskStatus.Failed    => "✗"
      case TaskStatus.Cancelled => "·"

  def rowKey(row: TaskRow, index: Int): String =
    if row.id.nonEmpty then row.id.value else (index + 1).toString

  def fold(params: Json, current: List[TaskRow]): Option[List[TaskRow]] =
    updateObj(params).flatMap(foldUpdate(_, current))

  def notices(before: List[TaskRow], after: List[TaskRow]): List[HostMsg] =
    after.flatMap { row =>
      val was = before.find(_.id == row.id)
      if TaskStatus.isLive(row.status) then None
      else if was.exists(w => !TaskStatus.isLive(w.status) && w.status == row.status) then None
      else if row.kind == TaskKind.Subagent then None
      else if was.exists(w => TaskStatus.isLive(w.status)) || was.isEmpty then
        Some(HostMsg.TaskNotice(noticeLabel(row)))
      else None
    }

  def parseLoop(args: String): Either[String, LoopSpec] =
    val t = args.trim
    if t.isEmpty then Left(LoopUsage)
    else
      val i = t.indexWhere(_.isWhitespace)
      if i < 0 then Left(LoopUsage)
      else
        val raw    = t.take(i)
        val prompt = t.drop(i).trim
        if prompt.isEmpty then Left(LoopUsage)
        else interval(raw).map(d => LoopSpec(d.max(MinLoop), prompt))
  end parseLoop

  def humanInterval(d: Duration): String =
    val sec = d.toSeconds.max(1)
    if sec % 86400 == 0 then s"every ${sec / 86400}d"
    else if sec % 3600 == 0 then s"every ${sec / 3600}h"
    else if sec % 60 == 0 then s"every ${sec / 60}m"
    else s"every ${sec}s"

  private def interval(raw: String): Either[String, Duration] =
    val t    = raw.trim.toLowerCase
    val num  = t.takeWhile(_.isDigit)
    val unit = t.drop(num.length)
    num.toIntOption.filter(_ > 0).toRight(LoopUsage).flatMap { n =>
      unit match
        case "s" | "sec" | "secs" | "second" | "seconds" => Right(n.seconds)
        case "m" | "min" | "mins" | "minute" | "minutes" => Right(n.minutes)
        case "h" | "hr" | "hrs" | "hour" | "hours"       => Right(n.hours)
        case "d" | "day" | "days"                        => Right(n.days)
        case _                                           => Left(LoopUsage)
    }
  end interval

  private def foldUpdate(obj: Json.Obj, current: List[TaskRow]): Option[List[TaskRow]] =
    str(obj, "sessionUpdate") match
      case Some("task_backgrounded") =>
        fromBackgrounded(obj).map(upsert(current, _))
      case Some("task_completed") =>
        Some(complete(current, obj))
      case Some("scheduled_task_created") =>
        fromScheduled(obj).map(upsert(current, _))
      case Some("scheduled_task_deleted") =>
        str(obj, "task_id").map(id => remove(current, TaskId(id)))
      case Some("subagent_spawned") =>
        fromSubagent(obj).map(upsert(current, _))
      case Some("subagent_finished") =>
        Some(finishSubagent(current, obj))
      case _ => None

  private def fromBackgrounded(obj: Json.Obj): Option[TaskRow] =
    val id    = str(obj, "task_id").orElse(str(obj, "tool_call_id")).getOrElse("")
    val label =
      str(obj, "description").orElse(str(obj, "command")).getOrElse("")
    if id.isEmpty || label.isEmpty then None
    else
      Some(
        TaskRow(
          id = TaskId(id),
          kind = TaskKind.Command,
          status = TaskStatus.Running,
          label = label,
          detail = str(obj, "command").filter(_ != label).getOrElse(""),
        )
      )
    end if
  end fromBackgrounded

  private def complete(current: List[TaskRow], obj: Json.Obj): List[TaskRow] =
    val snap = obj.fields.collectFirst { case ("task_snapshot", o: Json.Obj) => o }
    val id   =
      snap.flatMap(s => str(s, "task_id")).orElse(str(obj, "task_id")).getOrElse("")
    val label =
      snap.flatMap(s => str(s, "command").orElse(str(s, "description"))).getOrElse("")
    if id.isEmpty then current
    else
      val row = current.find(_.id.value == id) match
        case Some(cur) =>
          cur.copy(status = TaskStatus.Completed, label = if label.nonEmpty then label else cur.label)
        case None =>
          TaskRow(TaskId(id), TaskKind.Command, TaskStatus.Completed, if label.nonEmpty then label else id)
      upsert(current, row)
  end complete

  private def fromScheduled(obj: Json.Obj): Option[TaskRow] =
    val id     = str(obj, "task_id").getOrElse("")
    val prompt = str(obj, "prompt").getOrElse("")
    if id.isEmpty || prompt.isEmpty then None
    else
      Some(
        TaskRow(
          id = TaskId(id),
          kind = TaskKind.Loop,
          status = TaskStatus.Running,
          label = prompt,
          detail = str(obj, "human_schedule").getOrElse(""),
        )
      )
    end if
  end fromScheduled

  private def fromSubagent(obj: Json.Obj): Option[TaskRow] =
    val id    = str(obj, "subagent_id").orElse(str(obj, "child_session_id")).getOrElse("")
    val label =
      str(obj, "description").orElse(str(obj, "subagent_type")).getOrElse("")
    if id.isEmpty || label.isEmpty then None
    else
      val kind  = str(obj, "role").orElse(str(obj, "subagent_type"))
      val model = str(obj, "model")
      val bits  = List(kind, model).flatten
      Some(
        TaskRow(
          id = TaskId(id),
          kind = TaskKind.Subagent,
          status = TaskStatus.Running,
          label = label,
          detail = bits.mkString(" · "),
        )
      )
    end if
  end fromSubagent

  private def finishSubagent(current: List[TaskRow], obj: Json.Obj): List[TaskRow] =
    val id = str(obj, "subagent_id").orElse(str(obj, "child_session_id")).getOrElse("")
    if id.isEmpty then current
    else
      val status = TaskStatus.fromWire(str(obj, "status").getOrElse("completed"))
      current.find(_.id.value == id) match
        case Some(cur) => upsert(current, cur.copy(status = status))
        case None      =>
          upsert(
            current,
            TaskRow(TaskId(id), TaskKind.Subagent, status, str(obj, "description").getOrElse(id)),
          )
    end if
  end finishSubagent

  private def merge(old: TaskRow, next: TaskRow): TaskRow =
    next.copy(
      label = if next.label.nonEmpty then next.label else old.label,
      detail = if next.detail.nonEmpty then next.detail else old.detail,
      owned = old.owned || next.owned,
    )

  private def count(rows: List[TaskRow], kind: TaskKind, one: String, many: String): Option[String] =
    val n = rows.count(_.kind == kind)
    if n <= 0 then None
    else if n == 1 then Some(s"1 $one")
    else Some(s"$n $many")

  private def noticeLabel(row: TaskRow): String =
    if row.kind == TaskKind.Subagent then lifecycle(row)
    else
      val word =
        row.status match
          case TaskStatus.Completed => "completed"
          case TaskStatus.Failed    => "failed"
          case TaskStatus.Cancelled => "cancelled"
          case TaskStatus.Running   => "running"
      s"Task $word · ${clip(row.label)}"

  private def clip(s: String, cap: Int = 80): String =
    val t = s.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
    if t.length <= cap then t else t.take((cap - 1).max(0)) + "…"

  private def updateObj(params: Json): Option[Json.Obj] =
    params match
      case Json.Obj(fs) =>
        fs.collectFirst { case ("update", o: Json.Obj) => o }
          .orElse(
            Some(params).collect { case o: Json.Obj => o }
          )
      case _ => None

  private def str(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (k, Json.Str(s)) if k == key => s.trim }.filter(_.nonEmpty)
end Tasks
