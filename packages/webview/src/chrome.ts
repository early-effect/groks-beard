export type ModeOption = {
  readonly id: string
  readonly name: string
}

export type ReasoningChoice = {
  readonly value: string
  readonly name: string
}

export type ModelOption = {
  readonly modelId: string
  readonly name: string
  readonly description?: string
  readonly reasoning?: {
    readonly current: string
    readonly options: ReadonlyArray<ReasoningChoice>
  }
}

const MODE_NAMES: Record<string, string> = {
  normal: "Normal",
  plan: "Plan",
  "always-approve": "Always approve",
  ask: "Ask",
  auto: "Auto",
}

export const titleFromId = (id: string): string => {
  const known = MODE_NAMES[id]
  if (known !== undefined) return known
  return id
    .split(/[-_]/g)
    .filter((part) => part.length > 0)
    .map((part) => part.slice(0, 1).toUpperCase() + part.slice(1))
    .join(" ")
}

export const modeLabel = (
  modeId: string,
  modes: ReadonlyArray<ModeOption> = [],
): string => {
  const match = modes.find((mode) => mode.id === modeId)
  if (match !== undefined && match.name !== "") return match.name
  return titleFromId(modeId === "" ? "normal" : modeId)
}

export const isMacPlatform = (platform = ""): boolean => /Mac|iPhone|iPad/i.test(platform)

export const addSelectionShortcut = (
  platform = typeof navigator !== "undefined" ? navigator.platform : "",
): string => isMacPlatform(platform) ? "⌘⇧;" : "Ctrl+Shift+;"

export const sendShortcut = (
  ctrlEnterToSend: boolean,
  platform = typeof navigator !== "undefined" ? navigator.platform : "",
): string => {
  const enter = isMacPlatform(platform) ? "⌘↩" : "Ctrl+Enter"
  return ctrlEnterToSend ? enter : "Enter"
}

export const modeTip = (modeId: string): string => {
  switch (modeId) {
    case "normal":
      return "Ask before Grok runs tools"
    case "auto":
      return "Approve safe tools automatically"
    case "plan":
      return "Draft a plan before code edits"
    case "always-approve":
      return "Skip permission prompts for this session"
    default:
      return `Switch to ${titleFromId(modeId)}`
  }
}

export const permissionTip = (option: { readonly name: string; readonly kind: string }): string => {
  switch (option.kind) {
    case "allow_once":
      return `${option.name}: allow this once`
    case "allow_always":
      return `${option.name}: allow this for the rest of the session`
    case "reject_once":
      return `${option.name}: skip this once`
    case "reject_always":
      return `${option.name}: deny this for the rest of the session`
    default:
      return option.name
  }
}

export const mcpNeedsFolderTrust = (
  servers: ReadonlyArray<{
    readonly checks: ReadonlyArray<{ readonly label: string; readonly passed: boolean }>
  }>,
): boolean =>
  servers.some((server) =>
    server.checks.some((check) => !check.passed && /folder untrusted/i.test(check.label))
  )

export type EditorContextView = {
  readonly path: string
  readonly startLine: number
  readonly startCol: number
  readonly endLine: number
  readonly endCol: number
  readonly hasSelection: boolean
  readonly hasRange: boolean
  readonly excerpt?: string
}

export const editorContextKind = (ctx: EditorContextView): "File" | "Selection" =>
  ctx.hasSelection ? "Selection" : "File"

export const editorCaretLabel = (ctx: EditorContextView): string =>
  `${ctx.startLine}:${ctx.startCol}`

export const editorSelectionRange = (ctx: EditorContextView): string => {
  if (ctx.startLine === ctx.endLine) return `${ctx.startLine}:${ctx.startCol}-${ctx.endCol}`
  return `${ctx.startLine}:${ctx.startCol}-${ctx.endLine}:${ctx.endCol}`
}

export const clipExcerpt = (text: string, max = 48): string => {
  const one = text.replace(/\s+/g, " ").trim()
  if (one.length <= max) return one
  return `${one.slice(0, max).trimEnd()}…`
}

export const editorSelectionLabel = (ctx: EditorContextView): string => {
  if (ctx.hasSelection && ctx.hasRange) return editorSelectionRange(ctx)
  if (ctx.excerpt !== undefined && ctx.excerpt.trim() !== "") return clipExcerpt(ctx.excerpt)
  if (ctx.hasSelection) return editorSelectionRange(ctx)
  return editorCaretLabel(ctx)
}

export const editorContextLabel = (ctx: EditorContextView): string => {
  if (ctx.hasSelection && ctx.hasRange) return `${ctx.path}:${editorSelectionRange(ctx)}`
  if (ctx.hasSelection && ctx.excerpt !== undefined && ctx.excerpt.trim() !== "") {
    return `${ctx.path}: ${clipExcerpt(ctx.excerpt, 80)}`
  }
  if (ctx.hasSelection) return `${ctx.path}:${editorSelectionRange(ctx)}`
  return `${ctx.path}:${editorCaretLabel(ctx)}`
}

export const selectionAlreadyChipped = (
  ctx: EditorContextView,
  chips: ReadonlyArray<{
    readonly path: string
    readonly startLine?: number
    readonly endLine?: number
  }>,
): boolean => {
  if (!ctx.hasSelection) return false
  return chips.some((chip) => {
    if (chip.path !== ctx.path) return false
    if (ctx.hasRange) {
      return chip.startLine === ctx.startLine && chip.endLine === ctx.endLine
    }
    return chip.startLine === undefined && chip.endLine === undefined
  })
}

export const mcpToolSummary = (
  tools: ReadonlyArray<{ readonly enabled: boolean }>,
): string => {
  const on = tools.filter((tool) => tool.enabled).length
  if (on === tools.length) {
    return tools.length === 1 ? "1 tool · all on" : `${tools.length} tools · all on`
  }
  return `${on} of ${tools.length} tools on`
}

export const FALLBACK_REASONING_OPTIONS: ReadonlyArray<ReasoningChoice> = [
  { value: "low", name: "Low" },
  { value: "medium", name: "Medium" },
  { value: "high", name: "High" },
  { value: "xhigh", name: "Extra high" },
]

export const effortLabel = (choice: ReasoningChoice): string => {
  const stripped = choice.name.replace(/\s+effort$/i, "").trim()
  return stripped !== "" ? stripped : titleFromId(choice.value)
}

export const reasoningChoicesFor = (
  model: ModelOption | undefined,
  session?: { readonly current: string; readonly options: ReadonlyArray<ReasoningChoice> },
): { readonly current: string; readonly options: ReadonlyArray<ReasoningChoice> } => {
  const options = model?.reasoning !== undefined && model.reasoning.options.length > 0
    ? model.reasoning.options
    : session !== undefined && session.options.length > 0
    ? session.options
    : FALLBACK_REASONING_OPTIONS
  const preferred = session?.current ?? model?.reasoning?.current ?? "high"
  const current = options.some((item) => item.value === preferred)
    ? preferred
    : (options[0]?.value ?? "high")
  return { current, options }
}

export const modelChipLabel = (
  modelId: string | undefined,
  models: ReadonlyArray<ModelOption> = [],
  session?: { readonly current: string; readonly options: ReadonlyArray<ReasoningChoice> },
): string => {
  const name = modelLabel(modelId, models)
  const model = modelId !== undefined && modelId !== ""
    ? models.find((item) => item.modelId === modelId)
    : models[0]
  if (model?.reasoning === undefined || model.reasoning.options.length === 0) return name
  const reasoning = reasoningChoicesFor(model, session)
  const choice = reasoning.options.find((item) => item.value === reasoning.current)
  const effort = choice !== undefined ? effortLabel(choice) : titleFromId(reasoning.current)
  return `${name} · ${effort}`
}

export const compactTokens = (value: number): string => {
  if (value >= 1_000_000) {
    const millions = value / 1_000_000
    return Number.isInteger(millions) ? `${millions}M` : `${millions.toFixed(1)}M`
  }
  if (value >= 1000) {
    const thousands = value / 1000
    return Number.isInteger(thousands) ? `${thousands}k` : `${thousands.toFixed(1)}k`
  }
  return String(value)
}

export const occupancyPercent = (used: number, size: number): number =>
  size > 0 ? Math.min(100, Math.round((used / size) * 100)) : 0

export const occupancyLabel = (used: number, size: number): string =>
  `${compactTokens(used)} / ${compactTokens(size)} · ${occupancyPercent(used, size)}%`

export const TOOL_CLIP = 1600
export const TOOL_TAIL = 4

export const splitToolTail = <T>(
  tools: ReadonlyArray<T>,
  tail = TOOL_TAIL,
): { readonly earlier: ReadonlyArray<T>; readonly visible: ReadonlyArray<T> } => {
  if (tools.length <= tail) return { earlier: [], visible: tools }
  return {
    earlier: tools.slice(0, tools.length - tail),
    visible: tools.slice(tools.length - tail),
  }
}

export const toolRollupLabel = (count: number): string =>
  count === 1 ? "1 earlier tool" : `${count} earlier tools`

export const clipText = (
  text: string,
  limit = TOOL_CLIP,
): { readonly shown: string; readonly clipped: boolean } => {
  if (text.length <= limit) return { shown: text, clipped: false }
  return { shown: `${text.slice(0, limit)}\n…`, clipped: true }
}

export const occupancyTone = (used: number, size: number): "ok" | "warn" | "hot" => {
  const percent = occupancyPercent(used, size)
  if (percent >= 85) return "hot"
  if (percent >= 70) return "warn"
  return "ok"
}

export const modelLabel = (
  modelId: string | undefined,
  models: ReadonlyArray<ModelOption> = [],
): string => {
  if (modelId !== undefined && modelId !== "") {
    const match = models.find((model) => model.modelId === modelId)
    if (match !== undefined && match.name !== "") return match.name
    return modelId
  }
  const first = models[0]
  return first !== undefined && first.name !== "" ? first.name : "Model"
}
