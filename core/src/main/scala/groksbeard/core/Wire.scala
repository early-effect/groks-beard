package groksbeard.core

import zio.json.*
import zio.json.ast.Json

/** Closed HostMsg/WebviewMsg codec. Decode failure is an error, never a silent drop. */
object Wire:
  val Decode: String = "decode"

  def host(raw: String): Either[String, HostMsg] =
    hostMsgs(raw).flatMap {
      case msg :: _ => Right(msg)
      case Nil      => Left("Could not read host message (empty).")
    }

  def hostMsgs(raw: String): Either[String, List[HostMsg]] =
    raw.fromJson[Json] match
      case Left(err)              => Left(s"Could not read host message ($err).")
      case Right(Json.Str(inner)) =>
        val trimmed = inner.trim
        if trimmed.startsWith("{") || trimmed.startsWith("[") then hostMsgs(inner)
        else Left("Could not read host message (expected object).")
      case Right(obj: Json.Obj) =>
        tagOf(obj) match
          case None =>
            Left("Could not read host message (missing _tag).")
          case Some("toolGroup") =>
            toolGroup(obj)
          case Some(tag) =>
            obj.toJson.fromJson[HostMsg] match
              case Right(msg) => Right(List(msg))
              case Left(err)  => Left(s"Could not read host message (_tag: $tag, $err).")
      case Right(_) => Left("Could not read host message (expected object).")

  def hostOrError(raw: String): HostMsg =
    host(raw) match
      case Right(m)  => m
      case Left(err) => HostMsg.Error(err, Some(Decode))

  def webview(raw: String): Either[String, WebviewMsg] =
    raw.fromJson[Json] match
      case Left(err)            => Left(s"Could not read webview message ($err).")
      case Right(obj: Json.Obj) =>
        tagOf(obj) match
          case None =>
            Left("Could not read webview message (missing _tag).")
          case Some(tag) =>
            obj.toJson.fromJson[WebviewMsg] match
              case Right(msg) => Right(msg)
              case Left(err)  => Left(s"Could not read webview message (_tag: $tag, $err).")
      case Right(_) => Left("Could not read webview message (expected object).")

  private def tagOf(obj: Json.Obj): Option[String] =
    obj.fields.collectFirst { case ("_tag", Json.Str(s)) => s }

  /** Previous batch encoding of one-or-more tool rows. Fold into ToolCall + ToolChunk. */
  private def toolGroup(obj: Json.Obj): Either[String, List[HostMsg]] =
    obj.as[ToolGroupWire] match
      case Left(err) => Left(s"Could not read host message (_tag: toolGroup, $err).")
      case Right(g)  =>
        Right(
          g.tools.flatMap { row =>
            val call = HostMsg.ToolCall(g.turnId, row.copy(output = None))
            val out  =
              row.output.filter(_.nonEmpty).map(t => HostMsg.ToolChunk(g.turnId, row.id, t, snapshot = true))
            call :: out.toList
          }
        )

  final case class ToolGroupWire(turnId: TurnId, tools: List[ToolRow]) derives JsonCodec
end Wire
