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

export const workspaceRelativePath = (absPath: string, workspaceRoot?: string): string => {
  if (workspaceRoot === undefined || workspaceRoot === "") {
    const parts = absPath.split(/[\\/]/)
    return parts[parts.length - 1] ?? absPath
  }
  const root = workspaceRoot.replace(/[\\/]+$/, "")
  const normalized = absPath.replace(/\\/g, "/")
  const prefix = `${root.replace(/\\/g, "/")}/`
  if (normalized.startsWith(prefix)) return normalized.slice(prefix.length)
  const parts = absPath.split(/[\\/]/)
  return parts[parts.length - 1] ?? absPath
}

export const chipFromSelection = (input: {
  readonly absPath: string
  readonly workspaceRoot?: string
  readonly startLine: number
  readonly endLine: number
  readonly languageId?: string
}): PromptChip =>
  new PromptChip({
    path: workspaceRelativePath(input.absPath, input.workspaceRoot),
    absPath: input.absPath,
    startLine: input.startLine,
    endLine: input.endLine,
    source: "selection",
    ...(input.languageId !== undefined ? { languageId: input.languageId } : {})
  })

export const chipFromFile = (input: {
  readonly absPath: string
  readonly workspaceRoot?: string
  readonly languageId?: string
  readonly source?: "file" | "active" | "mention"
}): PromptChip =>
  new PromptChip({
    path: workspaceRelativePath(input.absPath, input.workspaceRoot),
    absPath: input.absPath,
    source: input.source ?? "file",
    ...(input.languageId !== undefined ? { languageId: input.languageId } : {})
  })

export const buildPromptText = (text: string, chips: ReadonlyArray<PromptChip>): string => {
  const refs = chips.map(formatAtRef)
  return [...refs, text].filter((part) => part.length > 0).join("\n\n")
}

export const chipsForSend = (input: {
  readonly chips: ReadonlyArray<PromptChip>
  readonly activeFile?: PromptChip
  readonly includeActiveFileByDefault: boolean
}): ReadonlyArray<PromptChip> => {
  if (input.chips.length > 0) return input.chips
  if (input.includeActiveFileByDefault && input.activeFile !== undefined) return [input.activeFile]
  return []
}
