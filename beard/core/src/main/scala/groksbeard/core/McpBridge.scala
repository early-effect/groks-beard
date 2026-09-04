package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class BridgeRequest(id: String, tool: String, args: Json = Json.Obj()) derives JsonCodec

final case class BridgeErrorBody(message: String, @jsonField("_tag") tag: Option[String] = None) derives JsonCodec

final case class BridgeResponse(
    id: String,
    ok: Boolean,
    result: Option[Json] = None,
    error: Option[BridgeErrorBody] = None,
) derives JsonCodec

object McpBridge:
  def request(id: String, tool: String, args: Json): BridgeRequest =
    BridgeRequest(id, tool, args)

  def ok(id: String, result: Json): BridgeResponse =
    BridgeResponse(id, ok = true, result = Some(result))

  def fail(id: String, message: String, tag: Option[String] = None): BridgeResponse =
    BridgeResponse(id, ok = false, error = Some(BridgeErrorBody(message, tag)))

  def parseRequest(json: Json): Option[BridgeRequest] =
    json.as[BridgeRequest].toOption

  def parseResponse(json: Json): Either[String, Json] =
    json.as[BridgeResponse] match
      case Right(BridgeResponse(_, true, result, _)) => Right(result.getOrElse(Json.Obj()))
      case Right(BridgeResponse(_, false, _, error)) =>
        Left(error.map(_.message).getOrElse(McpToml.EditorDownMessage))
      case Left(_) => Left("invalid bridge response")
end McpBridge
