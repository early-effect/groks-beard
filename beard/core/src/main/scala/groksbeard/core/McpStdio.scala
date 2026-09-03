package groksbeard.core

import zio.json.*
import zio.json.ast.Json

enum McpAction:
  case Ignore
  case Reply(json: Json)
  case CallTool(id: Json, name: String, args: Json)

object McpStdio:
  val ProtocolVersion                = "2025-03-26"
  val SupportedVersions: Set[String] = Set("2024-11-05", "2025-03-26", "2025-06-18")
  val ServerName                     = "groks-beard"
  val ServerVersion                  = "0.2.0"

  def parseWorkspaceArg(argv: List[String]): Option[String] =
    argv.zipWithIndex
      .collectFirst {
        case (arg, i) if arg == "--workspace"           => argv.lift(i + 1)
        case (arg, _) if arg.startsWith("--workspace=") =>
          Some(arg.substring("--workspace=".length))
      }
      .flatten
      .filter(_.nonEmpty)

  def handle(message: Json, host: McpToolHost): Option[Json] =
    handle(message, host, (name, args) => McpTools.dispatch(name, args, host))

  def handle(
      message: Json,
      host: McpToolHost,
      call: (String, Json) => Either[String, Json],
  ): Option[Json] =
    val _ = host
    classify(message) match
      case McpAction.Ignore                   => None
      case McpAction.Reply(json)              => Some(json)
      case McpAction.CallTool(id, name, args) =>
        Some(wrapCall(id, name, call(name, args)))
  end handle

  def classify(message: Json): McpAction =
    message match
      case obj: Json.Obj =>
        val method = obj.get("method") match
          case Some(Json.Str(m)) => Some(m)
          case _                 => None
        val id = obj.get("id")
        (method, id) match
          case (Some("notifications/initialized" | "notifications/cancelled"), _) => McpAction.Ignore
          case (None, _) | (_, None)                                              => McpAction.Ignore
          case (Some(m), Some(rid))                                               =>
            classifyMethod(m, rid, obj.get("params").getOrElse(Json.Obj()))
      case _ => McpAction.Ignore

  def wrapCall(id: Json, name: String, result: Either[String, Json]): Json =
    val _ = name
    result match
      case Left(err) if err == McpToml.EditorDownMessage =>
        rpcError(id, -32002, McpToml.EditorDownMessage)
      case Left(err)  => ok(id, toolText(err, isError = true))
      case Right(res) => ok(id, toolTextJson(res, isError = false))

  private def classifyMethod(method: String, id: Json, params: Json): McpAction =
    method match
      case "initialize" =>
        val version = params match
          case obj: Json.Obj =>
            obj.get("protocolVersion") match
              case Some(Json.Str(v)) if SupportedVersions.contains(v) => v
              case _                                                  => ProtocolVersion
          case _ => ProtocolVersion
        McpAction.Reply(
          ok(
            id,
            Json.Obj(
              "protocolVersion" -> Json.Str(version),
              "capabilities"    -> Json.Obj("tools" -> Json.Obj()),
              "serverInfo"      -> Json.Obj("name" -> Json.Str(ServerName), "version" -> Json.Str(ServerVersion)),
            ),
          )
        )
      case "ping"       => McpAction.Reply(ok(id, Json.Obj()))
      case "tools/list" =>
        val tools = McpTools.Specs.map { spec =>
          Json.Obj(
            "name"        -> Json.Str(spec.name),
            "description" -> Json.Str(spec.description),
            "inputSchema" -> spec.inputSchema,
            "annotations" -> McpTools.Annotations,
          )
        }
        McpAction.Reply(ok(id, Json.Obj("tools" -> Json.Arr(tools*))))
      case "tools/call" =>
        val (name, args) = toolCallParams(params)
        McpTool.fromName(name) match
          case None    => McpAction.Reply(rpcError(id, -32602, s"Unknown tool: $name"))
          case Some(_) => McpAction.CallTool(id, name, args)
      case other => McpAction.Reply(rpcError(id, -32601, s"Method not found: $other"))

  def toolText(value: String, isError: Boolean): Json =
    Json.Obj(
      "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(value))),
      "isError" -> Json.Bool(isError),
    )

  def toolTextJson(value: Json, isError: Boolean): Json =
    val text = value match
      case Json.Str(s) => s
      case other       => other.toJson
    toolText(text, isError)

  private def toolCallParams(params: Json): (String, Json) =
    params match
      case obj: Json.Obj =>
        val name = obj.get("name") match
          case Some(Json.Str(n)) => n
          case _                 => ""
        val args = obj.get("arguments").getOrElse(Json.Obj())
        (name, args)
      case _ => ("", Json.Obj())

  private def ok(id: Json, result: Json): Json =
    Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result)

  private def rpcError(id: Json, code: Int, message: String): Json =
    Json.Obj(
      "jsonrpc" -> Json.Str("2.0"),
      "id"      -> id,
      "error"   -> Json.Obj("code" -> Json.Num(code), "message" -> Json.Str(message)),
    )
end McpStdio
