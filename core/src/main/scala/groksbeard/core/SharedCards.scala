package groksbeard.core

import zio.json.*
import zio.json.ast.Json

/** Cards a shared TUI already answered. The agent continues; Beard must drop the prompt. */
object SharedCards:
  def isCancelRequest(method: String): Boolean =
    val n = method.trim
    n == "$/cancel_request" || n == "cancel_request"

  def cancelRpcId(params: Json): Option[RpcId] =
    params match
      case obj: Json.Obj =>
        obj.fields.collectFirst {
          case ("id", Json.Str(s)) => RpcId.Str(s)
          case ("id", Json.Num(n)) => RpcId.Num(n.intValue)
        }
      case _ => None

  def resolvedPermission(
      pending: Map[RequestId, Json],
      toolCallId: ToolCallId,
      status: ToolStatus,
  ): List[RequestId] =
    if status == ToolStatus.Pending || toolCallId.isEmpty then Nil
    else
      pending.iterator.collect {
        case (id, params) if permissionTool(params).contains(toolCallId) => id
      }.toList

  def permissionTool(params: Json): Option[ToolCallId] =
    params.as[PermissionRequestParams].toOption.map(_.toolCall.toolCallId).filter(_.nonEmpty)

  def leavesPlan(mode: ModeId): Boolean =
    mode.nonEmpty && !mode.value.toLowerCase.contains("plan")
end SharedCards
