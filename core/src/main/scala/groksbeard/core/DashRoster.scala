package groksbeard.core

import scala.annotation.unused
import zio.json.*
import zio.json.ast.Json

final case class DashRow(
    id: SessionId,
    title: String,
    cwd: String = "",
    state: String = "idle",
    detail: String = "",
    activityMs: Long = 0,
) derives JsonCodec

object DashRoster:
  def fromList(json: Json, @unused cwd: String): List[DashRow] =
    val items =
      json match
        case obj: Json.Obj =>
          obj.fields.collectFirst { case ("sessions", Json.Arr(xs)) => xs.toList }.getOrElse(Nil)
        case Json.Arr(xs) => xs.toList
        case _            => Nil
    items.flatMap(one).sortBy(r => -r.activityMs)

  def forPicker(rows: List[DashRow], cwd: String): List[DashRow] =
    val norm = cwd.replaceAll("[\\\\/]+$", "")
    if norm.isEmpty then rows
    else rows.filter(r => r.cwd.isEmpty || r.cwd.replaceAll("[\\\\/]+$", "") == norm)

  def toSessionRow(row: DashRow): SessionRow =
    SessionRow(row.id, row.title, activityMs = row.activityMs, summary = Option(row.detail).filter(_.nonEmpty))

  private def one(json: Json): Option[DashRow] =
    json match
      case obj: Json.Obj =>
        val id =
          str(obj, "sessionId")
            .orElse(str(obj, "id"))
            .map(SessionId(_))
            .filter(_.nonEmpty)
        id.map { sid =>
          DashRow(
            id = sid,
            title = str(obj, "title").orElse(str(obj, "name")).getOrElse(sid.value),
            cwd = str(obj, "cwd").orElse(str(obj, "workingDirectory")).getOrElse(""),
            state = str(obj, "state").orElse(str(obj, "status")).getOrElse("idle"),
            detail = str(obj, "detail").orElse(str(obj, "activity")).getOrElse(""),
            activityMs = num(obj, "updatedAt").orElse(num(obj, "activityMs")).getOrElse(0L),
          )
        }
      case _ => None

  private def str(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (k, Json.Str(s)) if k == key && s.nonEmpty => s }

  private def num(obj: Json.Obj, key: String): Option[Long] =
    obj.fields
      .collectFirst {
        case (k, Json.Num(n)) if k == key => n.longValue
        case (k, Json.Str(s)) if k == key =>
          s.toLongOption.getOrElse(0L)
      }
      .filter(_ != 0L)
end DashRoster
