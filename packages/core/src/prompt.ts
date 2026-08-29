import { Schema } from "effect"
import { truncateToByteCap as truncateUtf8, utf8ByteLength } from "./utf8.js"

export class PromptChip extends Schema.Class<PromptChip>("PromptChip")({
  path: Schema.String,
  absPath: Schema.String,
  startLine: Schema.optionalKey(Schema.Int),
  endLine: Schema.optionalKey(Schema.Int),
  languageId: Schema.optionalKey(Schema.String),
  source: Schema.Literals(["selection", "file", "active", "mention"])
}) {}

export const CHIP_EMBED_BYTE_CAP = 32 * 1024

export const formatAtRef = (chip: PromptChip): string =>
  chip.startLine !== undefined && chip.endLine !== undefined
    ? `@${chip.path}:${chip.startLine}-${chip.endLine}`
    : `@${chip.path}`

export { utf8ByteLength }

export const truncateToByteCap = (text: string, cap: number = CHIP_EMBED_BYTE_CAP): string =>
  truncateUtf8(text, cap)
