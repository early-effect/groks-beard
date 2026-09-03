package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class RpcError(code: Int, message: String)

enum Rpc:
  case Request(id: Json, method: String, params: Json)
  case Response(id: Json, result: Option[Json] = None, error: Option[RpcError] = None)
  case Notify(method: String, params: Json)

object Rpc:
  val MethodNotFound: Int = -32601

  def ok(id: Json, result: Json): Rpc.Response =
    Rpc.Response(id, result = Some(result))

  def fail(id: Json, code: Int, message: String): Rpc.Response =
    Rpc.Response(id, error = Some(RpcError(code, message)))

  def notify(method: String, params: Json): Rpc.Notify =
    Rpc.Notify(method, params)

  def parse(line: String): Either[String, Rpc] =
    line.fromJson[Json].flatMap {
      case obj: Json.Obj =>
        val method = obj.get("method") match
          case Some(Json.Str(m)) => Some(m)
          case _                 => None
        val id = obj.get("id")
        (method, id) match
          case (Some(m), Some(rid)) =>
            Right(Rpc.Request(rid, m, obj.get("params").getOrElse(Json.Obj())))
          case (Some(m), None) =>
            Right(Rpc.Notify(m, obj.get("params").getOrElse(Json.Obj())))
          case (None, Some(rid)) =>
            val err = obj.get("error") match
              case Some(e: Json.Obj) =>
                val code = e.get("code") match
                  case Some(Json.Num(n)) => n.intValue
                  case _                 => 0
                val message = e.get("message") match
                  case Some(Json.Str(s)) => s
                  case _                 => ""
                Some(RpcError(code, message))
              case _ => None
            Right(Rpc.Response(rid, obj.get("result"), err))
          case _ => Left("not a JSON-RPC message")
        end match
      case _ => Left("RPC line must be an object")
    }

  def toLine(msg: Rpc): String =
    val json = msg match
      case Rpc.Request(id, method, params) =>
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "id"      -> id,
          "method"  -> Json.Str(method),
          "params"  -> params,
        )
      case Rpc.Response(id, result, error) =>
        val extra: List[(String, Json)] =
          result.toList.map("result" -> _) ++ error.toList.map { e =>
            "error" -> Json.Obj("code" -> Json.Num(e.code), "message" -> Json.Str(e.message))
          }
        Json.Obj((List("jsonrpc" -> Json.Str("2.0"), "id" -> id) ++ extra)*)
      case Rpc.Notify(method, params) =>
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "method"  -> Json.Str(method),
          "params"  -> params,
        )
    json.toJson
  end toLine
end Rpc
