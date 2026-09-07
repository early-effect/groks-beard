package groksbeard.core

import ascent.squawk.Eq
import zio.json.*
import zio.json.ast.Json

final case class TodoEntry(
    content: String,
    status: TodoStatus = TodoStatus.Pending,
    priority: TodoPriority = TodoPriority.Medium,
    id: Option[String] = None,
) derives JsonCodec,
      Eq

object Todos:
  val Pending: TodoStatus    = TodoStatus.Pending
  val InProgress: TodoStatus = TodoStatus.InProgress
  val Completed: TodoStatus  = TodoStatus.Completed

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

  def kind(status: TodoStatus): TodoStatus = status

  def mark(status: TodoStatus): String =
    status match
      case TodoStatus.Completed  => "☑"
      case TodoStatus.InProgress => "▶"
      case TodoStatus.Pending    => "☐"

  def progress(entries: List[TodoEntry]): (Int, Int) =
    val n    = entries.size
    val done = entries.count(_.status == TodoStatus.Completed)
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
                status = str(obj, "status").map(TodoStatus.fromWire).getOrElse(TodoStatus.Pending),
                priority = str(obj, "priority").map(TodoPriority.fromWire).getOrElse(TodoPriority.Medium),
                id = str(obj, "id"),
              )
            )
        }
      case _ => None

  private def normalize(entry: TodoEntry): TodoEntry =
    entry.copy(
      content = entry.content.trim,
      status = entry.status,
      priority = entry.priority,
      id = entry.id.map(_.trim).filter(_.nonEmpty),
    )

  private def str(obj: Json.Obj, key: String): Option[String] =
    field(obj, key).collect { case Json.Str(s) => s.trim }.filter(_.nonEmpty)

  private def field(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (k, v) if k == key => v }
end Todos
