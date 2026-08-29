import { Schema } from "effect"

export class CliNotFound extends Schema.TaggedError<CliNotFound>()("CliNotFound", {
  searched: Schema.Array(Schema.String),
}) {}

export class NodeNotFound extends Schema.TaggedError<NodeNotFound>()("NodeNotFound", {
  searched: Schema.Array(Schema.String),
}) {}

export class AcpError extends Schema.TaggedError<AcpError>()("AcpError", {
  method: Schema.String,
  code: Schema.optionalKey(Schema.Number),
  message: Schema.String,
}) {}

export class MethodNotFound extends Schema.TaggedError<MethodNotFound>()("MethodNotFound", {
  method: Schema.String,
}) {
  readonly jsonRpcCode = -32601
}

export class SessionLocked extends Schema.TaggedError<SessionLocked>()("SessionLocked", {
  sessionId: Schema.String,
  cwd: Schema.String,
}) {}

export class SessionLoadFailed
  extends Schema.TaggedError<SessionLoadFailed>()("SessionLoadFailed", {
    sessionId: Schema.String,
    reason: Schema.String,
  })
{}

export class DiffIncomplete extends Schema.TaggedError<DiffIncomplete>()("DiffIncomplete", {
  path: Schema.String,
  cause: Schema.String,
}) {}

export const MCP_EDITOR_DOWN_MESSAGE =
  "Open this workspace in VS Code or Cursor with Grok's Beard and run \"Grok's Beard: Enable TUI Bridge\"."

export class McpEditorDown extends Schema.TaggedError<McpEditorDown>()("McpEditorDown", {
  workspace: Schema.String,
}) {}

export class PermissionParked extends Schema.TaggedError<PermissionParked>()("PermissionParked", {
  requestId: Schema.String,
}) {}
