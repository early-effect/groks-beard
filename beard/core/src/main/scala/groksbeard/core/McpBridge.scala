package groksbeard.core

import zio.json.ast.Json

object McpBridge:
  def request(id: String, tool: String, args: Json): Json =
    Json.Obj("id" -> Json.Str(id), "tool" -> Json.Str(tool), "args" -> args)

  def parseRequest(json: Json): Option[(String, String, Json)] =
    json match
      case obj: Json.Obj =>
        (obj.get("id"), obj.get("tool")) match
          case (Some(Json.Str(id)), Some(Json.Str(tool))) =>
            Some((id, tool, obj.get("args").getOrElse(Json.Obj())))
          case (Some(Json.Num(n)), Some(Json.Str(tool))) =>
            Some((n.toString, tool, obj.get("args").getOrElse(Json.Obj())))
          case _ => None
      case _ => None

  def ok(id: String, result: Json): Json =
    Json.Obj("id" -> Json.Str(id), "ok" -> Json.Bool(true), "result" -> result)

  def fail(id: String, message: String, tag: Option[String] = None): Json =
    val err =
      Json.Obj(
        (List("message" -> Json.Str(message)) ++ tag.toList.map(t => "_tag" -> Json.Str(t)))*
      )
    Json.Obj("id" -> Json.Str(id), "ok" -> Json.Bool(false), "error" -> err)

  def parseResponse(json: Json): Either[String, Json] =
    json match
      case obj: Json.Obj =>
        obj.get("ok") match
          case Some(Json.Bool(true))  => Right(obj.get("result").getOrElse(Json.Obj()))
          case Some(Json.Bool(false)) =>
            val msg = obj.get("error") match
              case Some(e: Json.Obj) =>
                e.get("message") match
                  case Some(Json.Str(s)) => s
                  case _                 => McpToml.EditorDownMessage
              case _ => McpToml.EditorDownMessage
            Left(msg)
          case _ => Left("invalid bridge response")
      case _ => Left("invalid bridge response")
end McpBridge
