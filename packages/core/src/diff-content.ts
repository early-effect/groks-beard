import { FileChange, lineDiffStats } from "./changeset.js"
import { type DiffSides, expandDiffToWholeFile } from "./diff-expand.js"
import { utf8ByteLength } from "./utf8.js"

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null

export type AcpDiffBlock = {
  readonly path: string
  readonly oldText: string
  readonly newText: string
  readonly oldTextWasNull: boolean
}

export type ToolLocation = {
  readonly path: string
  readonly line?: number
}

export type ToolCallDiffs = {
  readonly toolCallId: string
  readonly title: string
  readonly kind: string
  readonly status: string
  readonly diffs: ReadonlyArray<AcpDiffBlock>
  readonly replaceAll: boolean
  readonly fromPath?: string
  readonly locations: ReadonlyArray<ToolLocation>
}

export type ReconstructedFileDiff = {
  readonly path: string
  readonly oldText: string
  readonly newText: string
  readonly firstChangedLine: number
  readonly wholeFile: boolean
  readonly kind: FileChange["kind"]
  readonly toolCallId: string
  readonly fromPath?: string
}

const stringField = (record: Record<string, unknown>, key: string): string | undefined =>
  typeof record[key] === "string" ? record[key] as string : undefined

export const diffsFromContent = (content: unknown): ReadonlyArray<AcpDiffBlock> => {
  if (!Array.isArray(content)) return []
  const out: Array<AcpDiffBlock> = []
  for (const item of content) {
    if (!isRecord(item) || item.type !== "diff") continue
    const path = stringField(item, "path")
    if (path === undefined) continue
    const oldRaw = item.oldText
    const oldTextWasNull = oldRaw === null || oldRaw === undefined
    const oldText = typeof oldRaw === "string" ? oldRaw : ""
    const newText = typeof item.newText === "string" ? item.newText : ""
    out.push({ path, oldText, newText, oldTextWasNull })
  }
  return out
}

export const diffsFromRawInput = (rawInput: unknown): ReadonlyArray<AcpDiffBlock> => {
  if (!isRecord(rawInput)) return []
  const path = stringField(rawInput, "path")
  if (path === undefined) return []
  const oldRaw = rawInput.old_string ?? rawInput.oldText
  const newRaw = rawInput.new_string ?? rawInput.newText ?? rawInput.contents
  const hasOld = "old_string" in rawInput || "oldText" in rawInput
  const hasNew = "new_string" in rawInput || "newText" in rawInput || "contents" in rawInput
  if (!hasOld && !hasNew) return []
  const oldTextWasNull = !hasOld || oldRaw === null || oldRaw === undefined
  return [{
    path,
    oldText: typeof oldRaw === "string" ? oldRaw : "",
    newText: typeof newRaw === "string" ? newRaw : "",
    oldTextWasNull,
  }]
}

export const replaceAllFromRawInput = (rawInput: unknown): boolean =>
  isRecord(rawInput) && rawInput.replace_all === true

export const fromPathFromRawInput = (rawInput: unknown): string | undefined => {
  if (!isRecord(rawInput)) return undefined
  return stringField(rawInput, "from_path")
    ?? stringField(rawInput, "fromPath")
    ?? stringField(rawInput, "from")
    ?? (stringField(rawInput, "destination") !== undefined
      ? stringField(rawInput, "source")
      : undefined)
}

export const locationsFromUnknown = (value: unknown): ReadonlyArray<ToolLocation> => {
  if (!Array.isArray(value)) return []
  const out: Array<ToolLocation> = []
  for (const item of value) {
    if (!isRecord(item)) continue
    const path = stringField(item, "path")
    if (path === undefined) continue
    const line = typeof item.line === "number" && Number.isInteger(item.line) && item.line > 0
      ? item.line
      : undefined
    out.push(line !== undefined ? { path, line } : { path })
  }
  return out
}

export const toolCallFromPermissionParams = (params: unknown): unknown => {
  if (!isRecord(params)) return params
  return params.toolCall !== undefined ? params.toolCall : params
}

export const permissionOptionKind = (params: unknown, optionId: string): string | undefined => {
  if (!isRecord(params) || !Array.isArray(params.options)) return undefined
  for (const item of params.options) {
    if (!isRecord(item) || item.optionId !== optionId) continue
    return typeof item.kind === "string" ? item.kind : undefined
  }
  return undefined
}

export const isRejectPermissionKind = (kind: string | undefined): boolean =>
  kind === "reject_once" || kind === "reject_always"

export const diffsFromToolCall = (toolCall: unknown): ToolCallDiffs => {
  const rec = isRecord(toolCall) ? toolCall : {}
  const contentDiffs = diffsFromContent(rec.content)
  const rawDiffs = diffsFromRawInput(rec.rawInput)
  const fromPath = fromPathFromRawInput(rec.rawInput)
  return {
    toolCallId: stringField(rec, "toolCallId") ?? "tool",
    title: stringField(rec, "title") ?? "Tool",
    kind: stringField(rec, "kind") ?? "other",
    status: stringField(rec, "status") ?? "pending",
    diffs: contentDiffs.length > 0 ? contentDiffs : rawDiffs,
    replaceAll: replaceAllFromRawInput(rec.rawInput),
    ...(fromPath !== undefined ? { fromPath } : {}),
    locations: locationsFromUnknown(rec.locations),
  }
}

export const diskIsBeforeFromStatus = (status: string): boolean => status !== "completed"

export const inferChangeKind = (input: {
  readonly toolKind?: string
  readonly fromPath?: string
  readonly oldText: string
  readonly newText: string
  readonly diskExists?: boolean
  readonly diskIsBefore?: boolean
}): FileChange["kind"] => {
  if (input.fromPath !== undefined || input.toolKind === "move") return "move"
  if (input.toolKind === "delete") {
    if (input.diskIsBefore === false && input.diskExists === true) return "modify"
    return "delete"
  }
  if (input.oldText === "" && input.newText !== "") return "add"
  return "modify"
}

const wholeFileSides = (oldText: string, newText: string): DiffSides => ({
  oldText,
  newText,
  firstChangedLine: 0,
  wholeFile: true,
})

const completePostWriteSides = (
  sides: DiffSides,
  input: {
    readonly diskIsBefore: boolean
    readonly diskText: string | undefined
    readonly oldRegion: string
    readonly newRegion: string
    readonly toolKind: string
  },
): DiffSides => {
  if (input.diskIsBefore || sides.wholeFile || input.newRegion !== "") return sides
  if (input.diskText === "") return wholeFileSides(input.oldRegion, input.diskText)
  if (input.toolKind === "delete" && input.diskText === undefined && input.oldRegion !== "") {
    return wholeFileSides(input.oldRegion, "")
  }
  return sides
}

export const expandAcpDiff = (input: {
  readonly diff: AcpDiffBlock
  readonly diskText: string | undefined
  readonly diskIsBefore: boolean
  readonly replaceAll: boolean
}): DiffSides =>
  expandDiffToWholeFile({
    diskText: input.diskText,
    oldRegion: input.diff.oldText,
    newRegion: input.diff.newText,
    diskIsBefore: input.diskIsBefore,
    ...(input.replaceAll ? { replaceAll: true } : {}),
  })

export const reconstructToolDiffs = (input: {
  readonly toolCall: unknown
  readonly diskText: (path: string) => string | undefined
  readonly diskIsBefore: boolean
}): ReadonlyArray<ReconstructedFileDiff> => {
  const extracted = diffsFromToolCall(input.toolCall)
  return extracted.diffs.map((diff) => {
    const diskText = input.diskText(diff.path)
    const sides = completePostWriteSides(
      expandAcpDiff({
        diff,
        diskText,
        diskIsBefore: input.diskIsBefore,
        replaceAll: extracted.replaceAll,
      }),
      {
        diskIsBefore: input.diskIsBefore,
        diskText,
        oldRegion: diff.oldText,
        newRegion: diff.newText,
        toolKind: extracted.kind,
      },
    )
    const kind = inferChangeKind({
      toolKind: extracted.kind,
      oldText: sides.oldText,
      newText: sides.newText,
      diskExists: diskText !== undefined,
      diskIsBefore: input.diskIsBefore,
      ...(extracted.fromPath !== undefined ? { fromPath: extracted.fromPath } : {}),
    })
    return {
      path: diff.path,
      oldText: sides.oldText,
      newText: sides.newText,
      firstChangedLine: sides.firstChangedLine,
      wholeFile: sides.wholeFile,
      kind,
      toolCallId: extracted.toolCallId,
      ...(extracted.fromPath !== undefined ? { fromPath: extracted.fromPath } : {}),
    }
  })
}

export const fileChangeFromReconstructed = (diff: ReconstructedFileDiff): FileChange => {
  const stats = lineDiffStats(diff.oldText, diff.newText)
  return new FileChange({
    path: diff.path,
    kind: diff.kind,
    additions: stats.additions,
    deletions: stats.deletions,
    wholeFile: diff.wholeFile,
    toolCallId: diff.toolCallId,
    ...(diff.fromPath !== undefined ? { fromPath: diff.fromPath } : {}),
    ...(diff.kind === "add" ? {} : { oldSnapshot: diff.oldText }),
    ...(diff.kind === "delete" ? {} : { newSnapshot: diff.newText }),
  })
}

export const snapshotBytesFor = (change: FileChange): number => {
  const oldBytes = change.oldSnapshot !== undefined ? utf8ByteLength(change.oldSnapshot) : 0
  const newBytes = change.newSnapshot !== undefined ? utf8ByteLength(change.newSnapshot) : 0
  return oldBytes + newBytes
}
