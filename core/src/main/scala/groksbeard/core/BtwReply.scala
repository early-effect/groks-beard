package groksbeard.core

import zio.json.ast.Json

object BtwReply:
  val Missing: String = "CLI does not advertise /btw"

  def panel(aside: String, answer: String): String =
    val a = aside.trim
    val b = answer.trim
    if a.isEmpty then b
    else if b.isEmpty then a
    else s"$a\n\n$b"

  def answer(result: Option[Json]): String =
    result match
      case Some(Json.Str(s))   => s.trim
      case Some(obj: Json.Obj) =>
        str(obj, "text").orElse(str(obj, "message")).orElse(str(obj, "answer")).orElse(str(obj, "result")).getOrElse("")
      case _ => ""

  private def str(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst {
      case (k, Json.Str(s)) if k == key && s.trim.nonEmpty => s.trim
    }
end BtwReply
