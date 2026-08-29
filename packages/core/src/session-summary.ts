import { Schema } from "effect"

export class SessionInfo extends Schema.Class<SessionInfo>("SessionInfo")({
  id: Schema.String,
  cwd: Schema.String
}) {}

export class SessionSummary extends Schema.Class<SessionSummary>("SessionSummary")({
  info: SessionInfo,
  session_summary: Schema.optionalKey(Schema.String),
  generated_title: Schema.optionalKey(Schema.String),
  title_is_manual: Schema.optionalKey(Schema.Boolean),
  created_at: Schema.optionalKey(Schema.String),
  updated_at: Schema.optionalKey(Schema.String),
  last_active_at: Schema.optionalKey(Schema.String),
  num_messages: Schema.optionalKey(Schema.Number),
  num_chat_messages: Schema.optionalKey(Schema.Number),
  current_model_id: Schema.optionalKey(Schema.String),
  parent_session_id: Schema.optionalKey(Schema.String),
  agent_name: Schema.optionalKey(Schema.String),
  last_turn_summary: Schema.optionalKey(Schema.String),
  last_recap: Schema.optionalKey(Schema.String),
  sandbox_profile: Schema.optionalKey(Schema.String),
  reasoning_effort: Schema.optionalKey(Schema.String),
  grok_home: Schema.optionalKey(Schema.String)
}) {}

export const decodeSessionSummary = Schema.decodeUnknownSync(SessionSummary)
