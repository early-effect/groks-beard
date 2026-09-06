package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class TodoEntry(
    content: String,
    status: String = "pending",
    priority: String = "medium",
    id: Option[String] = None,
) derives JsonCodec

object Todos:
  val Pending: String    = "pending"
  val InProgress: String = "in_progress"
  val Completed: String  = "completed"

  def fromPlanJson(json: Json): List[TodoEntry] =
    json match
      case obj: Json.Obj =>
        field(obj, "todos") match
          case Some(items) => fromTodosField(items)
          case None        => fromTodosField(json)
      case Json.Arr(items) => items.toList.flatMap(fromItem)
      case _               => Nil

  def fromEntries(entries: List[TodoEntry]): List[TodoEntry] =
    entries.map(normalize).filter(_.content.nonEmpty)

  def kind(status: String): String =
    status.trim.toLowerCase.replace('-', '_').replace(' ', '_') match
      case "completed" | "complete" | "done"                 => Completed
      case "in_progress" | "inprogress" | "active" | "doing" => InProgress
      case _                                                 => Pending

  def mark(status: String): String =
    kind(status) match
      case Completed  => "☑"
      case InProgress => "▶"
      case _          => "☐"

  def progress(entries: List[TodoEntry]): (Int, Int) =
    val n    = entries.size
    val done = entries.count(e => kind(e.status) == Completed)
    (done, n)

  def headline(entries: List[TodoEntry]): String =
    if entries.isEmpty then "Todos"
    else
      val (done, n) = progress(entries)
      s"Todos $done/$n"

  def rowKey(entry: TodoEntry, index: Int): String =
    entry.id.filter(_.nonEmpty).getOrElse((index + 1).toString)

  private def fromTodosField(json: Json): List[TodoEntry] =
    json match
      case Json.Arr(items) => items.toList.flatMap(fromItem)
      case obj: Json.Obj   =>
        obj.fields.toList.flatMap { (id, value) =>
          fromItem(value).map { e =>
            if e.id.exists(_.nonEmpty) then e else e.copy(id = Some(id).filter(_.nonEmpty))
          }
        }
      case _ => Nil

  private def fromItem(json: Json): Option[TodoEntry] =
    json match
      case Json.Str(content) =>
        val t = content.trim
        if t.isEmpty then None else Some(TodoEntry(t))
      case obj: Json.Obj =>
        field(obj, "content").orElse(field(obj, "text")).orElse(field(obj, "title")).collect {
          case Json.Str(s) if s.trim.nonEmpty =>
            normalize(
              TodoEntry(
                content = s.trim,
                status = str(obj, "status").getOrElse(Pending),
                priority = str(obj, "priority").getOrElse("medium"),
                id = str(obj, "id"),
              )
            )
        }
      case _ => None

  private def normalize(entry: TodoEntry): TodoEntry =
    entry.copy(
      content = entry.content.trim,
      status = kind(entry.status),
      priority = entry.priority.trim.toLowerCase match
        case "high" | "low" => entry.priority.trim.toLowerCase
        case _              => "medium",
      id = entry.id.map(_.trim).filter(_.nonEmpty),
    )

  private def str(obj: Json.Obj, key: String): Option[String] =
    field(obj, key).collect { case Json.Str(s) => s.trim }.filter(_.nonEmpty)

  private def field(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (k, v) if k == key => v }
end Todos
