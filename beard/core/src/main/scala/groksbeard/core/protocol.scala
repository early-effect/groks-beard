package groksbeard.core

import zio.json.*
import zio.json.ast.Json

/** Closed host → UI messages. Discriminator is `_tag`. */
enum HostMsg:
  case Ready
  case SessionMeta(sessionId: String, title: String, modeId: String)

object HostMsg:
  given JsonEncoder[HostMsg] =
    JsonEncoder[Json].contramap {
      case HostMsg.Ready =>
        Json.Obj("_tag" -> Json.Str("ready"))
      case HostMsg.SessionMeta(sessionId, title, modeId) =>
        Json.Obj(
          "_tag"      -> Json.Str("sessionMeta"),
          "sessionId" -> Json.Str(sessionId),
          "title"     -> Json.Str(title),
          "modeId"    -> Json.Str(modeId),
        )
    }

  given JsonDecoder[HostMsg] =
    JsonDecoder[Json].mapOrFail {
      case obj: Json.Obj =>
        obj.get("_tag") match
          case Some(Json.Str("ready"))       => Right(HostMsg.Ready)
          case Some(Json.Str("sessionMeta")) =>
            for
              sessionId <- stringField(obj, "sessionId")
              title     <- stringField(obj, "title")
              modeId    <- stringField(obj, "modeId")
            yield HostMsg.SessionMeta(sessionId, title, modeId)
          case Some(Json.Str(other)) => Left(s"unknown HostMsg _tag: $other")
          case _                     => Left("HostMsg missing _tag")
      case _ => Left("HostMsg must be an object")
    }

  private def stringField(obj: Json.Obj, key: String): Either[String, String] =
    obj.get(key) match
      case Some(Json.Str(value)) => Right(value)
      case _                     => Left(s"HostMsg.$key must be a string")
end HostMsg

/** Closed UI → host messages. Discriminator is `_tag`. */
enum WebviewMsg:
  case Ready
  case Send(text: String)

object WebviewMsg:
  given JsonEncoder[WebviewMsg] =
    JsonEncoder[Json].contramap {
      case WebviewMsg.Ready      => Json.Obj("_tag" -> Json.Str("ready"))
      case WebviewMsg.Send(text) => Json.Obj("_tag" -> Json.Str("send"), "text" -> Json.Str(text))
    }

  given JsonDecoder[WebviewMsg] =
    JsonDecoder[Json].mapOrFail {
      case obj: Json.Obj =>
        obj.get("_tag") match
          case Some(Json.Str("ready")) => Right(WebviewMsg.Ready)
          case Some(Json.Str("send"))  =>
            obj.get("text") match
              case Some(Json.Str(text)) => Right(WebviewMsg.Send(text))
              case _                    => Left("WebviewMsg.send missing text")
          case Some(Json.Str(other)) => Left(s"unknown WebviewMsg _tag: $other")
          case _                     => Left("WebviewMsg missing _tag")
      case _ => Left("WebviewMsg must be an object")
    }
end WebviewMsg
