package groksbeard.core

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SharedCardsSpec extends ZIOSpecDefault:
  def spec =
    suite("SharedCards")(
      test("resolvedPermission ignores pending status") {
        val params = PermissionRequestParams(toolCall = AcpToolCall(toolCallId = ToolCallId("call_1"))).asJson
        val pending = Map(RequestId("perm-1") -> params)
        assertTrue(
          SharedCards.resolvedPermission(pending, "call_1", ToolStatus.Pending).isEmpty,
          SharedCards.resolvedPermission(pending, "call_1", ToolStatus.Completed) == List(RequestId("perm-1")),
          SharedCards.resolvedPermission(pending, "call_1", ToolStatus.InProgress) == List(RequestId("perm-1")),
          SharedCards.resolvedPermission(pending, "other", ToolStatus.Completed).isEmpty,
        )
      },
      test("leavesPlan is true once the session is no longer in plan") {
        assertTrue(
          !SharedCards.leavesPlan(ModeId.Plan),
          SharedCards.leavesPlan(ModeId.Normal),
          SharedCards.leavesPlan(ModeId.AlwaysApprove),
          !SharedCards.leavesPlan(ModeId.empty),
        )
      },
      test("cancel_request ids parse") {
        assertTrue(
          SharedCards.isCancelRequest("$/cancel_request"),
          SharedCards.cancelRpcId(Json.Obj("id" -> Json.Str("perm-1"))).contains(RpcId.Str("perm-1")),
          SharedCards.cancelRpcId(Json.Obj("id" -> Json.Num(3))).contains(RpcId.Num(3)),
        )
      },
    )
end SharedCardsSpec
