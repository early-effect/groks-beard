package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class RpcError(code: Int, message: String) derives JsonCodec

enum RpcId:
  case Str(value: String)
  case Num(value: Int)

object RpcId:
  given JsonCodec[RpcId] = JsonCodec(
    JsonEncoder[Json].contramap {
      case Str(s) => Json.Str(s)
      case Num(n) => Json.Num(n)
    },
    JsonDecoder[Json].mapOrFail {
      case Json.Str(s) => Right(Str(s))
      case Json.Num(n) => Right(Num(n.intValue))
      case _           => Left("JSON-RPC id must be a string or number")
    },
  )

  def key(id: RpcId): String = id.toJson
end RpcId

final case class JsonRpcCall(
    jsonrpc: String = "2.0",
    id: RpcId,
    method: String,
    params: Json = Json.Obj(),
) derives JsonCodec

final case class JsonRpcNotify(
    jsonrpc: String = "2.0",
    method: String,
    params: Json = Json.Obj(),
) derives JsonCodec

final case class JsonRpcResponse(
    jsonrpc: String = "2.0",
    id: RpcId,
    result: Option[Json] = None,
    error: Option[RpcError] = None,
) derives JsonCodec

final case class JsonRpcIncoming(
    jsonrpc: Option[String] = None,
    id: Option[RpcId] = None,
    method: Option[String] = None,
    params: Option[Json] = None,
    result: Option[Json] = None,
    error: Option[RpcError] = None,
) derives JsonCodec

enum Rpc:
  case Request(id: RpcId, method: String, params: Json)
  case Response(id: RpcId, result: Option[Json] = None, error: Option[RpcError] = None)
  case Notify(method: String, params: Json)

object Rpc:
  val MethodNotFound: Int = -32601

  def ok(id: RpcId, result: Json): Rpc.Response =
    Rpc.Response(id, result = Some(result))

  def fail(id: RpcId, code: Int, message: String): Rpc.Response =
    Rpc.Response(id, error = Some(RpcError(code, message)))

  def notify(method: String, params: Json): Rpc.Notify =
    Rpc.Notify(method, params)

  def request[A: JsonEncoder](id: RpcId, method: String, params: A): Rpc.Request =
    Rpc.Request(id, method, params.asJson)

  def notifyOf[A: JsonEncoder](method: String, params: A): Rpc.Notify =
    Rpc.Notify(method, params.asJson)

  def parse(line: String): Either[String, Rpc] =
    line.fromJson[JsonRpcIncoming].flatMap { msg =>
      (msg.method, msg.id) match
        case (Some(method), Some(id)) =>
          Right(Rpc.Request(id, method, msg.params.getOrElse(Json.Obj())))
        case (Some(method), None) =>
          Right(Rpc.Notify(method, msg.params.getOrElse(Json.Obj())))
        case (None, Some(id)) =>
          Right(Rpc.Response(id, msg.result, msg.error))
        case _ => Left("not a JSON-RPC message")
    }

  def toLine(msg: Rpc): String =
    msg match
      case Rpc.Request(id, method, params) => JsonRpcCall(id = id, method = method, params = params).toJson
      case Rpc.Response(id, result, error) => JsonRpcResponse(id = id, result = result, error = error).toJson
      case Rpc.Notify(method, params)      => JsonRpcNotify(method = method, params = params).toJson
end Rpc
