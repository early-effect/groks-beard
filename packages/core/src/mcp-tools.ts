import { Schema } from "effect"
import { formatAtRef, PromptChip } from "./prompt.js"
import { truncateToByteCap, utf8ByteLength } from "./utf8.js"

export const SELECTION_TEXT_CAP_BYTES = 20_000

export const McpToolName = Schema.Literals([
  "editor_workspace_root",
  "editor_selection",
  "editor_open_files",
  "editor_reveal",
  "editor_open_diff",
  "editor_show_changes",
])
export type McpToolName = typeof McpToolName.Type
export const MCP_TOOL_NAMES: ReadonlyArray<McpToolName> = [
  "editor_workspace_root",
  "editor_selection",
  "editor_open_files",
  "editor_reveal",
  "editor_open_diff",
  "editor_show_changes",
]

export class EditorWorkspaceRootResult
  extends Schema.Class<EditorWorkspaceRootResult>("EditorWorkspaceRootResult")({
    root: Schema.String,
  })
{}

export class EditorSelectionResult
  extends Schema.Class<EditorSelectionResult>("EditorSelectionResult")({
    path: Schema.optionalKey(Schema.String),
    absPath: Schema.optionalKey(Schema.String),
    startLine: Schema.optionalKey(Schema.Number),
    endLine: Schema.optionalKey(Schema.Number),
    startCol: Schema.optionalKey(Schema.Number),
    endCol: Schema.optionalKey(Schema.Number),
    text: Schema.optionalKey(Schema.String),
    truncated: Schema.Boolean,
    languageId: Schema.optionalKey(Schema.String),
    atRef: Schema.optionalKey(Schema.String),
  })
{}

export class EditorOpenFilesResult
  extends Schema.Class<EditorOpenFilesResult>("EditorOpenFilesResult")({
    tabs: Schema.Array(Schema.String),
    active: Schema.optionalKey(Schema.String),
  })
{}

export class EditorRevealArgs extends Schema.Class<EditorRevealArgs>("EditorRevealArgs")({
  path: Schema.String,
  line: Schema.optionalKey(Schema.Int),
}) {}

export class EditorOpenDiffArgs extends Schema.Class<EditorOpenDiffArgs>("EditorOpenDiffArgs")({
  path: Schema.String,
  line: Schema.optionalKey(Schema.Int),
}) {}

export class EditorOpenDiffResult
  extends Schema.Class<EditorOpenDiffResult>("EditorOpenDiffResult")({
    ok: Schema.Boolean,
    reason: Schema.optionalKey(Schema.String),
  })
{}

export class EditorShowChangesFile
  extends Schema.Class<EditorShowChangesFile>("EditorShowChangesFile")({
    path: Schema.String,
    kind: Schema.Literals(["add", "modify", "delete", "move"]),
  })
{}

export class EditorShowChangesArgs
  extends Schema.Class<EditorShowChangesArgs>("EditorShowChangesArgs")({
    title: Schema.optionalKey(Schema.String),
    files: Schema.NonEmptyArray(EditorShowChangesFile),
  })
{}

export class EditorShowChangesResult
  extends Schema.Class<EditorShowChangesResult>("EditorShowChangesResult")({
    ok: Schema.Boolean,
    shown: Schema.Number,
  })
{}

export class EditorRevealResult extends Schema.Class<EditorRevealResult>("EditorRevealResult")({
  ok: Schema.Literals([true]),
}) {}

export type SelectionInput = {
  readonly path?: string
  readonly absPath?: string
  readonly startLine?: number
  readonly endLine?: number
  readonly startCol?: number
  readonly endCol?: number
  readonly text?: string
  readonly languageId?: string
}

export const editorSelectionFrom = (input: SelectionInput): EditorSelectionResult => {
  const truncated = input.text !== undefined
    && utf8ByteLength(input.text) > SELECTION_TEXT_CAP_BYTES
  const text = input.text !== undefined
    ? truncateToByteCap(input.text, SELECTION_TEXT_CAP_BYTES)
    : undefined
  const atRef = input.path !== undefined
      && input.startLine !== undefined
      && input.endLine !== undefined
    ? `@${input.path}:${input.startLine}-${input.endLine}`
    : undefined
  return new EditorSelectionResult({
    truncated,
    ...(input.path !== undefined ? { path: input.path } : {}),
    ...(input.absPath !== undefined ? { absPath: input.absPath } : {}),
    ...(input.startLine !== undefined ? { startLine: input.startLine } : {}),
    ...(input.endLine !== undefined ? { endLine: input.endLine } : {}),
    ...(input.startCol !== undefined ? { startCol: input.startCol } : {}),
    ...(input.endCol !== undefined ? { endCol: input.endCol } : {}),
    ...(text !== undefined ? { text } : {}),
    ...(input.languageId !== undefined ? { languageId: input.languageId } : {}),
    ...(atRef !== undefined ? { atRef } : {}),
  })
}

export const editorSelectionFromChip = (
  chip: PromptChip,
  text?: string,
): EditorSelectionResult =>
  editorSelectionFrom({
    path: chip.path,
    absPath: chip.absPath,
    ...(chip.startLine !== undefined ? { startLine: chip.startLine } : {}),
    ...(chip.endLine !== undefined ? { endLine: chip.endLine } : {}),
    ...(chip.languageId !== undefined ? { languageId: chip.languageId } : {}),
    ...(text !== undefined ? { text } : {}),
  })

export const preferPendingSelection = (
  pending: PromptChip | undefined,
  live: SelectionInput | undefined,
  pendingText?: string,
): EditorSelectionResult => {
  if (pending !== undefined) return editorSelectionFromChip(pending, pendingText ?? live?.text)
  if (live !== undefined) return editorSelectionFrom(live)
  return new EditorSelectionResult({ truncated: false })
}

export const selectionAtRef = (result: EditorSelectionResult): string | undefined => {
  if (result.atRef !== undefined) return result.atRef
  if (result.path === undefined) return undefined
  return formatAtRef(
    new PromptChip({
      path: result.path,
      absPath: result.absPath ?? result.path,
      source: "selection",
      ...(result.startLine !== undefined ? { startLine: result.startLine } : {}),
      ...(result.endLine !== undefined ? { endLine: result.endLine } : {}),
    }),
  )
}
