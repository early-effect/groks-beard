import { McpToolName } from "@groks-beard/core"
import { Schema } from "effect"

export class BridgeRequest extends Schema.Class<BridgeRequest>("BridgeRequest")({
  id: Schema.String,
  tool: McpToolName,
  args: Schema.optionalKey(Schema.Unknown),
}) {}

export class BridgeErrorBody extends Schema.Class<BridgeErrorBody>("BridgeErrorBody")({
  message: Schema.String,
  _tag: Schema.optionalKey(Schema.String),
}) {}

export class BridgeResponse extends Schema.Class<BridgeResponse>("BridgeResponse")({
  id: Schema.String,
  ok: Schema.Boolean,
  result: Schema.optionalKey(Schema.Unknown),
  error: Schema.optionalKey(BridgeErrorBody),
}) {}

export const decodeBridgeRequest = Schema.decodeUnknownSync(BridgeRequest)
export const decodeBridgeResponse = Schema.decodeUnknownSync(BridgeResponse)
export const encodeBridgeRequest = Schema.encodeSync(BridgeRequest)
export const encodeBridgeResponse = Schema.encodeSync(BridgeResponse)
