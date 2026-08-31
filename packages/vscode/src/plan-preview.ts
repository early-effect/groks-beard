import { encodeCwd } from "@groks-beard/core"
import { join } from "node:path"

export const PLAN_FILE_NAME = "plan.md"
export const FALLBACK_PLAN_FILE = "groks-beard-plan.md"

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null

export const isGrokPlanPath = (absPath: string): boolean => {
  const posix = absPath.replace(/\\/g, "/")
  const base = posix.slice(posix.lastIndexOf("/") + 1)
  if (base === FALLBACK_PLAN_FILE) return true
  return /\/sessions\/[^/]+\/[^/]+\/plan\.md$/i.test(posix)
}

export const markdownPreviewViewType = (viewType: string): boolean =>
  /markdown\.preview/i.test(viewType)

export const tabLabelLooksLikePlan = (label: string): boolean =>
  /(^|[\s(])plan\.md(\s|$)/i.test(label.trim()) || /^plan\.md$/i.test(label.trim())

const fsPathOf = (value: unknown): string | undefined => {
  if (!isRecord(value)) return undefined
  if (typeof value.fsPath === "string" && value.fsPath !== "") return value.fsPath
  if (isRecord(value.uri) && typeof value.uri.fsPath === "string" && value.uri.fsPath !== "") {
    return value.uri.fsPath
  }
  return undefined
}

export const planPathFromTabInput = (
  input: unknown,
  options: { readonly label?: string; readonly knownPlanPath?: string } = {},
): string | undefined => {
  if (!isRecord(input)) return undefined
  const viewType = typeof input.viewType === "string" ? input.viewType : undefined
  if (viewType === undefined || !markdownPreviewViewType(viewType)) return undefined
  const fsPath = fsPathOf(input)
  if (fsPath !== undefined && isGrokPlanPath(fsPath)) return fsPath
  if (fsPath !== undefined) return undefined
  if (options.knownPlanPath === undefined) return undefined
  if (
    options.label !== undefined && options.label !== "" && !tabLabelLooksLikePlan(options.label)
  ) {
    return undefined
  }
  return options.knownPlanPath
}

export const resolveChatEditorFile = (input: {
  readonly activeTab?: { readonly label?: string; readonly input?: unknown }
  readonly editor?: { readonly fsPath: string; readonly scheme: string }
  readonly knownPlanPath?: string
}): { readonly absPath: string; readonly fromPlanPreview: boolean } | undefined => {
  const fromTab = planPathFromTabInput(input.activeTab?.input, {
    ...(input.activeTab?.label !== undefined ? { label: input.activeTab.label } : {}),
    ...(input.knownPlanPath !== undefined ? { knownPlanPath: input.knownPlanPath } : {}),
  })
  if (fromTab !== undefined) return { absPath: fromTab, fromPlanPreview: true }
  if (
    input.editor !== undefined
    && (input.editor.scheme === "file" || input.editor.scheme === "untitled")
  ) {
    return { absPath: input.editor.fsPath, fromPlanPreview: false }
  }
  return undefined
}

export const grokSessionPlanPath = (
  home: string,
  cwd: string,
  sessionId: string,
): string => join(home, "sessions", encodeCwd(cwd), sessionId, PLAN_FILE_NAME)

export const resolvePlanFile = (input: {
  readonly home: string
  readonly cwd: string
  readonly sessionId?: string
  readonly tmpDir: string
  readonly exists: (path: string) => boolean
}): { readonly path: string; readonly fromSession: boolean } => {
  if (input.sessionId !== undefined && input.sessionId !== "") {
    const session = grokSessionPlanPath(input.home, input.cwd, input.sessionId)
    if (input.exists(session)) return { path: session, fromSession: true }
  }
  return { path: join(input.tmpDir, FALLBACK_PLAN_FILE), fromSession: false }
}
