import type { ToolLocation } from "@groks-beard/core"
import { ORIGINAL_SCHEME, PROPOSED_SCHEME } from "./virtual-docs.js"

export type FollowAlongPlan = {
  readonly preserveFocus: boolean
  readonly reveals: ReadonlyArray<{
    readonly path: string
    readonly line?: number
  }>
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null

const schemeOf = (value: unknown): string | undefined => {
  if (!isRecord(value) || typeof value.scheme !== "string") return undefined
  return value.scheme
}

export const isVirtualDiffScheme = (scheme: string | undefined): boolean =>
  scheme === ORIGINAL_SCHEME || scheme === PROPOSED_SCHEME

export const schemesFromTabInput = (input: unknown): Array<string> => {
  if (!isRecord(input)) return []
  const out: Array<string> = []
  const direct = schemeOf(input.uri)
  if (direct !== undefined) out.push(direct)
  const original = schemeOf(input.original)
  if (original !== undefined) out.push(original)
  const modified = schemeOf(input.modified)
  if (modified !== undefined) out.push(modified)
  for (const key of ["textDiffs", "resources"] as const) {
    const list = input[key]
    if (!Array.isArray(list)) continue
    for (const item of list) out.push(...schemesFromTabInput(item))
  }
  return out
}

export const isDiffEditorInput = (input: unknown): boolean => {
  if (!isRecord(input)) return false
  const name = typeof input.constructor?.name === "string" ? input.constructor.name : ""
  if (name.includes("TextDiff") || name.includes("MultiDiff")) return true
  if ("original" in input && "modified" in input) return true
  if (Array.isArray(input.textDiffs) || Array.isArray(input.resources)) return true
  return false
}

export const detectDiffEditor = (active: {
  readonly scheme?: string
  readonly tabInput?: unknown
  readonly schemesInActiveGroup?: ReadonlyArray<string>
}): boolean => {
  if (isVirtualDiffScheme(active.scheme)) return true
  if (isDiffEditorInput(active.tabInput)) return true
  return (active.schemesInActiveGroup ?? []).some(isVirtualDiffScheme)
}

const FOLLOW_KINDS = new Set(["edit", "delete", "move"])

export const shouldFollowAlong = (
  kind: string,
  options: { readonly hasDiffs?: boolean; readonly readOnly?: boolean } = {},
): boolean => {
  if (options.readOnly === true) return false
  if (options.hasDiffs === true) return true
  return FOLLOW_KINDS.has(kind.toLowerCase())
}

export const planFollowAlong = (
  locations: ReadonlyArray<ToolLocation>,
  active: {
    readonly scheme?: string
    readonly inDiffEditor?: boolean
    readonly tabInput?: unknown
    readonly schemesInActiveGroup?: ReadonlyArray<string>
  },
): FollowAlongPlan => ({
  preserveFocus: active.inDiffEditor === true || detectDiffEditor(active),
  reveals: locations.map((location) =>
    location.line !== undefined
      ? { path: location.path, line: location.line }
      : { path: location.path }
  ),
})
