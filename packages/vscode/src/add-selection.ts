import { markdownPreviewViewType } from "./plan-preview.js"

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value)

const fsPathOf = (value: unknown): string | undefined => {
  if (!isRecord(value)) return undefined
  if (typeof value.fsPath === "string" && value.fsPath !== "") return value.fsPath
  if (isRecord(value.uri) && typeof value.uri.fsPath === "string" && value.uri.fsPath !== "") {
    return value.uri.fsPath
  }
  return undefined
}

export type VisibleEditorSelection = {
  readonly fsPath: string
  readonly empty: boolean
  readonly startLine: number
  readonly startCol: number
  readonly endLine: number
  readonly endCol: number
  readonly excerpt?: string
  readonly languageId?: string
}

export type PreviewSelectionPayload = {
  readonly excerpt: string
  readonly startLine?: number
  readonly endLine?: number
}

export type AddSelection = {
  readonly absPath: string
  readonly excerpt: string
  readonly startLine?: number
  readonly endLine?: number
  readonly languageId?: string
}

export const markdownPreviewPathFromTabInput = (input: unknown): string | undefined => {
  if (!isRecord(input)) return undefined
  const viewType = typeof input.viewType === "string" ? input.viewType : undefined
  if (viewType === undefined || !markdownPreviewViewType(viewType)) return undefined
  return fsPathOf(input)
}

const basenameOf = (absPath: string): string => {
  const posix = absPath.replace(/\\/g, "/")
  return posix.slice(posix.lastIndexOf("/") + 1)
}

const previewLabelFile = (label: string): string | undefined => {
  const trimmed = label.trim().replace(/^preview\s+/i, "").trim()
  if (trimmed === "" || !/\.(md|markdown)$/i.test(trimmed)) return undefined
  return trimmed
}

export const previewResourcePath = (input: {
  readonly tabInput?: unknown
  readonly tabLabel?: string
  readonly editors: ReadonlyArray<{ readonly fsPath: string }>
}): string | undefined => {
  const fromTab = markdownPreviewPathFromTabInput(input.tabInput)
  if (fromTab !== undefined) return fromTab
  const viewType = isRecord(input.tabInput) && typeof input.tabInput.viewType === "string"
    ? input.tabInput.viewType
    : undefined
  if (viewType === undefined || !markdownPreviewViewType(viewType)) return undefined
  const name = input.tabLabel !== undefined ? previewLabelFile(input.tabLabel) : undefined
  if (name === undefined) return undefined
  const match = input.editors.find((editor) => basenameOf(editor.fsPath) === name)
  return match?.fsPath
}

export const linesFromPreviewDataLine = (
  anchorLine: number | undefined,
  focusLine: number | undefined,
): { readonly startLine: number; readonly endLine: number } | undefined => {
  if (anchorLine === undefined && focusLine === undefined) return undefined
  const anchor = anchorLine ?? focusLine
  const focus = focusLine ?? anchorLine
  if (anchor === undefined || focus === undefined) return undefined
  if (!Number.isFinite(anchor) || !Number.isFinite(focus) || anchor < 0 || focus < 0) {
    return undefined
  }
  return {
    startLine: Math.min(anchor, focus) + 1,
    endLine: Math.max(anchor, focus) + 1,
  }
}

export const parsePreviewSelection = (value: unknown): PreviewSelectionPayload | undefined => {
  const rec = isRecord(value)
    ? value
    : Array.isArray(value) && isRecord(value[0])
    ? value[0]
    : undefined
  if (rec === undefined) return undefined
  const excerpt = typeof rec.excerpt === "string"
    ? rec.excerpt
    : typeof rec.text === "string"
    ? rec.text
    : ""
  const trimmed = excerpt.trim()
  if (trimmed === "") return undefined
  const startLine = typeof rec.startLine === "number" && Number.isFinite(rec.startLine)
      && rec.startLine > 0
    ? Math.floor(rec.startLine)
    : undefined
  const endLine = typeof rec.endLine === "number" && Number.isFinite(rec.endLine) && rec.endLine > 0
    ? Math.floor(rec.endLine)
    : startLine
  return {
    excerpt: trimmed,
    ...(startLine !== undefined ? { startLine } : {}),
    ...(endLine !== undefined ? { endLine } : {}),
  }
}

const fromEditor = (editor: VisibleEditorSelection): AddSelection | undefined => {
  if (editor.empty) return undefined
  const excerpt = editor.excerpt ?? ""
  if (excerpt === "") return undefined
  return {
    absPath: editor.fsPath,
    excerpt,
    startLine: editor.startLine,
    endLine: editor.endLine,
    ...(editor.languageId !== undefined && editor.languageId !== ""
      ? { languageId: editor.languageId }
      : {}),
  }
}

export const selectionToAdd = (input: {
  readonly payload?: PreviewSelectionPayload
  readonly previewPath?: string
  readonly activeEditor?: VisibleEditorSelection
  readonly editors: ReadonlyArray<VisibleEditorSelection>
}): AddSelection | undefined => {
  if (input.previewPath !== undefined) {
    if (input.payload !== undefined) {
      return {
        absPath: input.previewPath,
        excerpt: input.payload.excerpt,
        ...(input.payload.startLine !== undefined ? { startLine: input.payload.startLine } : {}),
        ...(input.payload.endLine !== undefined ? { endLine: input.payload.endLine } : {}),
        languageId: "markdown",
      }
    }
    const match = input.editors.find((editor) =>
      editor.fsPath === input.previewPath && !editor.empty
    )
    return match !== undefined ? fromEditor(match) : undefined
  }
  if (input.activeEditor !== undefined) return fromEditor(input.activeEditor)
  return undefined
}
