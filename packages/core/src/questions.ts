import { Schema } from "effect"

export class QuestionOption extends Schema.Class<QuestionOption>("QuestionOption")({
  id: Schema.String,
  label: Schema.String
}) {}

export class AgentQuestion extends Schema.Class<AgentQuestion>("AgentQuestion")({
  id: Schema.String,
  prompt: Schema.String,
  options: Schema.Array(QuestionOption),
  allowMultiple: Schema.optionalKey(Schema.Boolean),
  allowFreeText: Schema.optionalKey(Schema.Boolean)
}) {}

export class AskUserQuestionRequest extends Schema.Class<AskUserQuestionRequest>("AskUserQuestionRequest")({
  sessionId: Schema.optionalKey(Schema.String),
  questions: Schema.NonEmptyArray(AgentQuestion)
}) {}

export class AskUserQuestionAnswer extends Schema.Class<AskUserQuestionAnswer>("AskUserQuestionAnswer")({
  questionId: Schema.String,
  optionIds: Schema.Array(Schema.String),
  freeText: Schema.optionalKey(Schema.String)
}) {}
