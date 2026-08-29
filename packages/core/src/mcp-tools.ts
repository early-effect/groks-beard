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

export class EditorOpenFilesArgs extends Schema.Class<EditorOpenFilesArgs>("EditorOpenFilesArgs")({
  cursor: Schema.optionalKey(Schema.String),
}) {}

export class EditorOpenFilesResult
  extends Schema.Class<EditorOpenFilesResult>("EditorOpenFilesResult")({
    tabs: Schema.Array(Schema.String),
    active: Schema.optionalKey(Schema.String),
    truncated: Schema.Boolean,
    nextCursor: Schema.optionalKey(Schema.String),
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

const parseOpenFilesCursor = (cursor: string | undefined): number => {
  if (cursor === undefined || cursor === "") return 0
  const parsed = Number.parseInt(cursor, 10)
  if (!Number.isFinite(parsed) || parsed < 0) return 0
  return parsed
}

export const pageOpenFiles = (input: {
  readonly tabs: ReadonlyArray<string>
  readonly active?: string
  readonly cursor?: string
  readonly capBytes?: number
}): EditorOpenFilesResult => {
  const cap = input.capBytes ?? SELECTION_TEXT_CAP_BYTES
  const start = Math.min(parseOpenFilesCursor(input.cursor), input.tabs.length)
  const rest = input.tabs.slice(start)
  const encode = (
    tabs: ReadonlyArray<string>,
    truncated: boolean,
    nextCursor: string | undefined,
  ): string =>
    JSON.stringify({
      tabs,
      truncated,
      ...(input.active !== undefined ? { active: input.active } : {}),
      ...(nextCursor !== undefined ? { nextCursor } : {}),
    })
  const taken: Array<string> = []
  for (let i = 0; i < rest.length; i++) {
    const tab = rest[i]
    if (tab === undefined) break
    const trial = [...taken, tab]
    const more = i + 1 < rest.length
    const nextCursor = more ? String(start + trial.length) : undefined
    if (utf8ByteLength(encode(trial, more, nextCursor)) > cap) {
      if (taken.length === 0) {
        return new EditorOpenFilesResult({
          tabs: [],
          truncated: true,
          nextCursor: String(start + 1),
          ...(input.active !== undefined ? { active: input.active } : {}),
        })
      }
      break
    }
    taken.push(tab)
  }
  const consumed = start + taken.length
  const truncated = consumed < input.tabs.length
  return new EditorOpenFilesResult({
    tabs: taken,
    truncated,
    ...(input.active !== undefined ? { active: input.active } : {}),
    ...(truncated ? { nextCursor: String(consumed) } : {}),
  })
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
