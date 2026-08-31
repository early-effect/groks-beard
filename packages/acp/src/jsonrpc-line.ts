import type { AnyMessage, AnyRequest, AnyResponse } from "@agentclientprotocol/sdk"

export const isJsonRpcResponse = (message: AnyMessage): message is AnyResponse =>
  "id" in message && !("method" in message)

export const isJsonRpcRequest = (message: AnyMessage): message is AnyRequest =>
  "method" in message && "id" in message

export const isSuccessResponse = (
  message: AnyResponse,
): message is AnyResponse & { result: unknown } => "result" in message
