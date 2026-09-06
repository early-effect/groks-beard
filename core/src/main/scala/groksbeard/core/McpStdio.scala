package groksbeard.core

import zio.json.*
import zio.json.ast.Json

enum McpAction:
  case Ignore
  case Reply(json: Json)
  case CallTool(id: RpcId, name: String, args: Json)

final case class McpInitializeParams(protocolVersion: Option[String] = None) derives JsonCodec
final case class McpToolsCapability() derives JsonCodec
final case class McpCapabilities(tools: McpToolsCapability = McpToolsCapability()) derives JsonCodec
final case class McpServerInfo(name: String, version: String) derives JsonCodec
final case class McpInitializeResult(
    protocolVersion: String,
    capabilities: McpCapabilities,
    serverInfo: McpServerInfo,
) derives JsonCodec
final case class McpToolsListResult(tools: List[McpListedTool]) derives JsonCodec
final case class McpToolCallParams(name: String, arguments: Json = Json.Obj()) derives JsonCodec
final case class McpTextContent(@jsonField("type") tpe: String = "text", text: String) derives JsonCodec
final case class McpToolCallResult(content: List[McpTextContent], isError: Boolean) derives JsonCodec

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
    message.as[JsonRpcIncoming] match
      case Left(_)         => McpAction.Ignore
      case Right(incoming) =>
        (incoming.method, incoming.id) match
          case (Some("notifications/initialized" | "notifications/cancelled"), _) => McpAction.Ignore
          case (None, _) | (_, None)                                              => McpAction.Ignore
          case (Some(method), Some(id))                                           =>
            classifyMethod(method, id, incoming.params.getOrElse(Json.Obj()))

  def wrapCall(id: RpcId, name: String, result: Either[String, Json]): Json =
    val _ = name
    result match
      case Left(err) if err == McpToml.EditorDownMessage =>
        rpcError(id, -32002, McpToml.EditorDownMessage)
      case Left(err)  => JsonRpcResponse(id = id, result = Some(toolText(err, isError = true))).asJson
      case Right(res) => JsonRpcResponse(id = id, result = Some(toolTextJson(res, isError = false))).asJson

  private def classifyMethod(method: String, id: RpcId, params: Json): McpAction =
    method match
      case "initialize" =>
        val requested = params.as[McpInitializeParams].toOption.flatMap(_.protocolVersion)
        val version   = requested.filter(SupportedVersions.contains).getOrElse(ProtocolVersion)
        McpAction.Reply(
          ok(
            id,
            McpInitializeResult(
              protocolVersion = version,
              capabilities = McpCapabilities(),
              serverInfo = McpServerInfo(ServerName, ServerVersion),
            ).asJson,
          )
        )
      case "ping"       => McpAction.Reply(ok(id, EmptyObject().asJson))
      case "tools/list" =>
        McpAction.Reply(ok(id, McpToolsListResult(McpTools.listed).asJson))
      case "tools/call" =>
        params.as[McpToolCallParams] match
          case Left(_)     => McpAction.Reply(rpcError(id, -32602, "Invalid params"))
          case Right(call) =>
            McpTool.fromName(call.name) match
              case None    => McpAction.Reply(rpcError(id, -32602, s"Unknown tool: ${call.name}"))
              case Some(_) => McpAction.CallTool(id, call.name, call.arguments)
      case other => McpAction.Reply(rpcError(id, -32601, s"Method not found: $other"))

  def toolText(value: String, isError: Boolean): Json =
    McpToolCallResult(List(McpTextContent(text = value)), isError).asJson

  def toolTextJson(value: Json, isError: Boolean): Json =
    val text = value match
      case Json.Str(s) => s
      case other       => other.toJson
    toolText(text, isError)

  private def ok(id: RpcId, result: Json): Json =
    JsonRpcResponse(id = id, result = Some(result)).asJson

  private def rpcError(id: RpcId, code: Int, message: String): Json =
    JsonRpcResponse(id = id, error = Some(RpcError(code, message))).asJson
end McpStdio
