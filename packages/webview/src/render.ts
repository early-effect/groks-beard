import { formatAtRef, type PromptChip } from "@groks-beard/core"
import {
  addSelectionShortcut,
  clipText,
  editorCaretLabel,
  editorContextLabel,
  type EditorContextView,
  editorSelectionLabel,
  effortLabel,
  mcpNeedsFolderTrust,
  mcpToolSummary,
  modeLabel,
  modelChipLabel,
  modeTip,
  occupancyLabel,
  occupancyPercent,
  occupancyTone,
  permissionTip,
  reasoningChoicesFor,
  selectionAlreadyChipped,
  sendShortcut,
  splitToolTail,
  toolRollupLabel,
} from "./chrome.js"
import { renderMarkdown } from "./markdown.js"
import { mentionChoices, mentionPopoverOpen, mentionQueryFromDraft } from "./mentions.js"
import { type ChatModel, turnIsRunning, type TurnView } from "./model.js"
import { filterSlashCommands, slashQueryFromDraft } from "./slash.js"
import { thoughtBlock } from "./thinking.js"

export type ComposerChip = {
  readonly path: string
  readonly absPath: string
  readonly startLine?: number
  readonly endLine?: number
  readonly languageId?: string
  readonly excerpt?: string
  readonly source: PromptChip["source"]
}

export type OpenMenu = "mode" | "model" | "settings" | undefined

export type RenderState = {
  readonly model: ChatModel
  readonly draft: string
  readonly ctrlEnterToSend: boolean
  readonly chips: ReadonlyArray<ComposerChip>
  readonly openMenu: OpenMenu
  readonly openModelSettings?: string
  readonly mentionsDismissed?: boolean
  readonly mentionIndex?: number
}

export type ChatShell = {
  readonly root: HTMLElement
  readonly transcript: HTMLElement
  readonly cards: HTMLElement
  readonly popovers: HTMLElement
  readonly status: HTMLElement
  readonly toast: HTMLElement
  readonly chips: HTMLElement
  readonly draft: HTMLTextAreaElement
  readonly composer: HTMLElement
  readonly bar: HTMLElement
  readonly context: HTMLElement
}

const el = <K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] => {
  const node = document.createElement(tag)
  if (className !== undefined) node.className = className
  if (text !== undefined) node.textContent = text
  return node
}

const svgIcon = (
  path: string,
  options: { readonly stroke?: boolean; readonly size?: number } = {},
): SVGSVGElement => {
  const size = options.size ?? 16
  const node = document.createElementNS("http://www.w3.org/2000/svg", "svg")
  node.setAttribute("viewBox", "0 0 16 16")
  node.setAttribute("width", String(size))
  node.setAttribute("height", String(size))
  node.setAttribute("aria-hidden", "true")
  const p = document.createElementNS("http://www.w3.org/2000/svg", "path")
  p.setAttribute("d", path)
  if (options.stroke === true) {
    node.setAttribute("fill", "none")
    p.setAttribute("fill", "none")
    p.setAttribute("stroke", "currentColor")
    p.setAttribute("stroke-width", "1.5")
    p.setAttribute("stroke-linecap", "round")
    p.setAttribute("stroke-linejoin", "round")
  } else {
    node.setAttribute("fill", "currentColor")
    p.setAttribute("fill", "currentColor")
  }
  node.append(p)
  return node
}

const PLUS_PATH = "M8 3.5v9M3.5 8h9"
const GEAR_PATH =
  "M6.4 1.7h3.2l.3 1.4c.4.1.8.3 1.1.6l1.4-.5 1.6 2.8-1.2.9c.1.4.1.8 0 1.2l1.2.9-1.6 2.8-1.4-.5c-.3.3-.7.5-1.1.6l-.3 1.4H6.4l-.3-1.4c-.4-.1-.8-.3-1.1-.6l-1.4.5-1.6-2.8 1.2-.9c-.1-.4-.1-.8 0-1.2l-1.2-.9 1.6-2.8 1.4.5c.3-.3.7-.5 1.1-.6zm1.6 4.1a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z"
const CHEVRON_PATH = "M4.5 6.2 8 9.7l3.5-3.5"
const SEND_PATH = "M8 3.2 12.8 8H9.5v4.8H6.5V8H3.2z"
const STOP_PATH = "M5 5h6v6H5z"

const FALLBACK_MODES = [
  { id: "normal", name: "Normal" },
  { id: "auto", name: "Auto" },
  { id: "plan", name: "Plan" },
  { id: "always-approve", name: "Always approve" },
] as const

const sessionModes = (
  session: ChatModel["session"],
): ReadonlyArray<{ id: string; name: string }> =>
  session?.availableModes !== undefined && session.availableModes.length > 0
    ? session.availableModes
    : FALLBACK_MODES

const iconButton = (
  className: string,
  action: string,
  title: string,
  path: string,
  options: { readonly stroke?: boolean } = {},
): HTMLButtonElement => {
  const button = el("button", className)
  button.type = "button"
  button.dataset.action = action
  button.title = title
  button.setAttribute("aria-label", title)
  button.append(svgIcon(path, { ...options, size: 14 }))
  return button
}

export const mountShell = (root: HTMLElement, ctrlEnterToSend: boolean): ChatShell => {
  const existing = root.querySelector("#transcript")
  if (existing !== null) {
    return {
      root,
      transcript: existing as HTMLElement,
      cards: root.querySelector(".cards") as HTMLElement,
      popovers: root.querySelector(".popovers") as HTMLElement,
      status: root.querySelector(".status") as HTMLElement,
      toast: root.querySelector(".toast") as HTMLElement,
      chips: root.querySelector(".chips") as HTMLElement,
      draft: root.querySelector("#draft") as HTMLTextAreaElement,
      composer: root.querySelector(".composer") as HTMLElement,
      bar: root.querySelector(".composer-bar") as HTMLElement,
      context: root.querySelector(".composer-context") as HTMLElement,
    }
  }
  const transcript = el("div", "transcript")
  transcript.id = "transcript"
  const cards = el("div", "cards")
  const status = el("div", "status")
  const dock = el("div", "dock")
  const popovers = el("div", "popovers")
  const composer = el("div", "composer")
  const chips = el("div", "chips")
  const draft = el("textarea")
  draft.id = "draft"
  draft.rows = 1
  draft.placeholder = ctrlEnterToSend
    ? "Ask Grok anything, @ to mention. Ctrl/Cmd+Enter to send."
    : "Ask Grok anything, @ to mention..."
  const bar = el("div", "composer-bar")
  const context = el("div", "composer-context")
  context.hidden = true
  composer.append(chips, draft, context, bar)
  const toast = el("div", "toast")
  toast.hidden = true
  dock.append(toast, popovers, composer)
  root.append(transcript, cards, status, dock)
  return { root, transcript, cards, popovers, status, toast, chips, draft, composer, bar, context }
}

const thoughtOpenTurns = (transcript: HTMLElement): Set<string> => {
  const open = new Set<string>()
  for (const details of transcript.querySelectorAll("details.thought[open]")) {
    const turnId = details.getAttribute("data-turn")
    if (turnId !== null) open.add(turnId)
  }
  return open
}

const rollupOpenTurns = (transcript: HTMLElement): Set<string> => {
  const open = new Set<string>()
  for (const details of transcript.querySelectorAll("details.tool-rollup[open]")) {
    if (!(details instanceof HTMLElement)) continue
    const turn = details.dataset.turn
    if (turn !== undefined) open.add(turn)
  }
  return open
}

const toolOpenIds = (transcript: HTMLElement): Set<string> => {
  const open = new Set<string>()
  for (const details of transcript.querySelectorAll("details.tool[open]")) {
    const id = details.getAttribute("data-tool")
    if (id !== null) open.add(id)
  }
  return open
}

const payloadBlock = (label: string, text: string): HTMLElement => {
  const wrap = el("div", "tool-payload")
  wrap.append(el("h4", undefined, label))
  const clipped = clipText(text)
  const pre = el("pre", "tool-stream")
  pre.textContent = clipped.shown
  wrap.append(pre)
  if (clipped.clipped) {
    const more = el("button", "tool-more", "Show all")
    more.type = "button"
    more.title = "Show the full tool payload"
    more.addEventListener("click", (event) => {
      event.preventDefault()
      event.stopPropagation()
      pre.textContent = text
      more.remove()
    })
    wrap.append(more)
  }
  return wrap
}

const renderTool = (tool: TurnView["tools"][number], open: boolean): HTMLElement => {
  const details = el("details", "tool")
  details.dataset.tool = tool.id
  details.open = open
  const title = tool.title !== "" ? tool.title : "Tool"
  const summary = tool.status !== "" && tool.status !== "pending"
    ? `${title} (${tool.status})`
    : title
  const summaryEl = el("summary", undefined, summary)
  summaryEl.title = tool.status !== "" ? `${title}: ${tool.status}` : title
  details.append(summaryEl)
  const body = el("div", "tool-body")
  if (tool.input !== undefined && tool.input !== "") {
    body.append(payloadBlock("Input", tool.input))
  }
  if (tool.output !== undefined && tool.output !== "") {
    body.append(payloadBlock("Output", tool.output))
  }
  if (body.childElementCount === 0) {
    body.append(el("p", "tool-empty", "No input or output yet."))
  }
  details.append(body)
  return details
}

const renderTurn = (
  turn: TurnView,
  wasOpen: boolean,
  openTools: ReadonlySet<string>,
  rollupOpen: boolean,
): HTMLElement => {
  const section = el("section", "turn")
  section.dataset.turn = turn.id
  if (turn.user !== undefined) {
    const user = el("div", "user")
    const refs = turn.user.chips.map(formatAtRef).filter((ref) => ref.length > 0)
    user.textContent = [...refs, turn.user.text].filter((part) => part.length > 0).join("\n")
    section.append(user)
  }
  const block = thoughtBlock(turn.thought, turn.stopReason !== undefined, wasOpen)
  if (block !== undefined) {
    const details = el("details", "thought")
    details.dataset.turn = turn.id
    details.open = block.open
    const summary = el("summary", undefined, block.summary)
    summary.title = block.open
      ? "Hide Grok's reasoning"
      : "Show Grok's reasoning"
    const pre = el("pre", "thought-stream")
    pre.textContent = block.stream
    details.append(summary, pre)
    section.append(details)
  }
  if (turn.tools.length > 0) {
    const list = el("div", "tools")
    const { earlier, visible } = splitToolTail(turn.tools)
    if (earlier.length > 0) {
      const rollup = el("details", "tool-rollup")
      rollup.dataset.turn = turn.id
      rollup.open = rollupOpen
      const rollupSummary = el("summary", undefined, toolRollupLabel(earlier.length))
      rollupSummary.title = "Show earlier tool calls from this turn"
      rollup.append(rollupSummary)
      const hidden = el("div", "tool-rollup-list")
      for (const tool of earlier) {
        hidden.append(renderTool(tool, openTools.has(tool.id)))
      }
      rollup.append(hidden)
      list.append(rollup)
    }
    for (const tool of visible) {
      list.append(renderTool(tool, openTools.has(tool.id)))
    }
    section.append(list)
  }
  if (turn.agent.length > 0) {
    const agent = el("div", "agent")
    agent.innerHTML = renderMarkdown(turn.agent)
    section.append(agent)
  }
  if (turn.stopReason !== undefined && turn.stopReason !== "end_turn") {
    section.append(el("div", "stop", turn.stopReason))
  }
  return section
}

const renderEmpty = (): HTMLElement => {
  const empty = el("div", "empty")
  const logo = document.body.dataset.logo
  if (logo !== undefined && logo !== "") {
    const img = el("img", "empty-logo")
    img.src = logo
    img.alt = "Grok's Beard"
    img.draggable = false
    empty.append(img)
  }
  empty.append(el("h1", "empty-title", "Grok's Beard"))
  empty.append(el("p", "empty-copy", "Ask Grok anything."))
  return empty
}

const renderCards = (cards: HTMLElement, model: ChatModel): void => {
  cards.replaceChildren()
  if (model.permission !== undefined) {
    const card = el("div", "card permission")
    card.dataset.requestId = model.permission.requestId
    card.append(el("h3", undefined, model.permission.title))
    model.permission.options.forEach((option, index) => {
      const button = el("button", undefined, `${index + 1} ${option.name}`)
      button.type = "button"
      button.title = permissionTip(option)
      button.dataset.action = "permissionChoice"
      button.dataset.optionId = option.optionId
      card.append(button)
    })
    if (model.permission.hasDiff) {
      const diff = el("button", "secondary", "Open diff")
      diff.type = "button"
      diff.title = "Review this edit as a diff"
      diff.dataset.action = "openDiff"
      card.append(diff)
    }
    cards.append(card)
  }
  if (model.plan !== undefined) {
    const card = el("div", "card plan")
    card.dataset.requestId = model.plan.requestId
    const pre = el("pre")
    pre.textContent = model.plan.planMarkdown
    const open = el("button", "plan-open", "Open plan")
    open.type = "button"
    open.title = "Open plan.md in Markdown Preview"
    open.dataset.action = "openPlan"
    card.append(pre, open)
    const planTips = {
      approved: "Accept this plan and let Grok continue",
      cancelled: "Send Grok back to revise the plan",
      abandoned: "Drop this plan and stay in the current mode",
    } as const
    for (
      const [verdict, label, klass] of [
        ["approved", "Approve", "plan-approve"],
        ["cancelled", "Request changes", "plan-revise"],
        ["abandoned", "Abandon", "plan-abandon"],
      ] as const
    ) {
      const button = el("button", klass, label)
      button.type = "button"
      button.title = planTips[verdict]
      button.dataset.action = "planVerdict"
      button.dataset.verdict = verdict
      card.append(button)
    }
    cards.append(card)
  }
  if (model.question !== undefined) {
    const card = el("div", "card question")
    card.dataset.requestId = model.question.requestId
    for (const question of model.question.questions) {
      card.append(el("p", undefined, question.prompt))
    }
    const dismiss = el("button", "secondary", "Dismiss")
    dismiss.type = "button"
    dismiss.title = "Dismiss this question without answering"
    dismiss.dataset.action = "questionDismiss"
    card.append(dismiss)
    cards.append(card)
  }
  if (model.elicit !== undefined) {
    const card = el("div", "card elicit")
    card.dataset.requestId = model.elicit.requestId
    card.append(el("h3", undefined, model.elicit.title))
    const accept = el("button", undefined, "Accept")
    accept.type = "button"
    accept.title = "Allow this MCP prompt"
    accept.dataset.action = "elicitAccept"
    const decline = el("button", "secondary", "Decline")
    decline.type = "button"
    decline.title = "Deny this MCP prompt"
    decline.dataset.action = "elicitDecline"
    card.append(accept, decline)
    cards.append(card)
  }
}

const chipSelect = (
  label: string,
  action: string,
  expanded: boolean,
  title: string,
): HTMLButtonElement => {
  const button = el("button", "mode-chip")
  button.type = "button"
  button.dataset.action = action
  button.title = `${title}: ${label}`
  button.setAttribute("aria-haspopup", "listbox")
  button.setAttribute("aria-expanded", expanded ? "true" : "false")
  const copy = el("span", "chip-copy")
  copy.append(el("span", "chip-kind", title), el("span", "label", label))
  button.append(copy, svgIcon(CHEVRON_PATH, { stroke: true, size: 12 }))
  return button
}

const menuList = (
  items: ReadonlyArray<
    {
      id: string
      label: string
      selected: boolean
      action: string
      key: string
      title?: string
    }
  >,
): HTMLUListElement => {
  const list = el("ul", "menu")
  list.setAttribute("role", "listbox")
  for (const item of items) {
    const row = el("li", item.selected ? "selected" : undefined, item.label)
    row.dataset.action = item.action
    row.dataset[item.key] = item.id
    if (item.title !== undefined) row.title = item.title
    row.setAttribute("role", "option")
    row.setAttribute("aria-selected", item.selected ? "true" : "false")
    list.append(row)
  }
  return list
}

const renderModelMenu = (state: RenderState): HTMLUListElement => {
  const models = state.model.session?.availableModels ?? []
  const currentId = state.model.session?.modelId
  const sessionReasoning = state.model.session?.reasoning
  const list = el("ul", "menu model-menu")
  list.setAttribute("role", "listbox")
  for (const model of models) {
    const selected = model.modelId === currentId
    const reasoning = reasoningChoicesFor(model, selected ? sessionReasoning : undefined)
    const item = el("li", selected ? "model-item selected" : "model-item")
    item.setAttribute("role", "option")
    item.setAttribute("aria-selected", selected ? "true" : "false")
    const row = el("div", "model-row")
    const pick = el("button", "model-pick", model.name)
    pick.type = "button"
    pick.title = model.description !== undefined && model.description !== ""
      ? `Use ${model.name}: ${model.description}`
      : `Use ${model.name}`
    pick.dataset.action = "setModel"
    pick.dataset.modelId = model.modelId
    const effort = el("button", "model-effort")
    effort.type = "button"
    effort.dataset.action = "toggleModelReasoning"
    effort.dataset.modelId = model.modelId
    const currentChoice = reasoning.options.find((option) => option.value === reasoning.current)
    const effortName = currentChoice !== undefined
      ? effortLabel(currentChoice)
      : effortLabel({ value: reasoning.current, name: reasoning.current })
    effort.title = `Reasoning: ${effortName}`
    effort.setAttribute("aria-label", `${model.name} reasoning ${effortName}`)
    effort.setAttribute(
      "aria-expanded",
      state.openModelSettings === model.modelId ? "true" : "false",
    )
    effort.append(
      el("span", undefined, effortName),
      svgIcon(CHEVRON_PATH, { stroke: true, size: 10 }),
    )
    row.append(pick, effort)
    item.append(row)
    if (state.openModelSettings === model.modelId) {
      const sub = el("ul", "menu-sub")
      sub.setAttribute("role", "listbox")
      for (const option of reasoning.options) {
        const choice = el(
          "li",
          option.value === reasoning.current ? "selected" : undefined,
          effortLabel(option),
        )
        choice.title = `Set reasoning to ${effortLabel(option)}`
        choice.dataset.action = "setReasoning"
        choice.dataset.value = option.value
        choice.dataset.modelId = model.modelId
        choice.setAttribute("role", "option")
        choice.setAttribute("aria-selected", option.value === reasoning.current ? "true" : "false")
        sub.append(choice)
      }
      item.append(sub)
    }
    list.append(item)
  }
  return list
}

const resizeDraft = (draft: HTMLTextAreaElement): void => {
  draft.style.height = "auto"
  draft.style.height = `${Math.min(Math.max(draft.scrollHeight, 40), 160)}px`
}

const fileCountLabel = (count: number): string => count === 1 ? "1 file" : `${count} files`

const renderToast = (toast: HTMLElement, model: ChatModel): void => {
  const changes = model.changes
  if (changes === undefined || changes.fileCount <= 0) {
    toast.hidden = true
    toast.replaceChildren()
    return
  }
  toast.hidden = false
  toast.replaceChildren()
  const body = el("button", "toast-body")
  body.type = "button"
  body.title = "Review pending file changes"
  body.dataset.action = "openChanges"
  const stats = el("span", "toast-stats")
  stats.append(
    el("span", "toast-add", `+${changes.additions}`),
    el("span", "toast-del", `\u2212${changes.deletions}`),
  )
  body.append(el("span", "toast-count", fileCountLabel(changes.fileCount)), stats)
  const keep = el("button", "toast-keep", "Keep all")
  keep.type = "button"
  keep.title = "Keep Grok's edits in the workspace"
  keep.dataset.action = "keepAllPending"
  const commit = el("button", "toast-commit", "Commit")
  commit.type = "button"
  commit.title = "Commit pending Grok file changes"
  commit.dataset.action = "commitAllPending"
  toast.append(body, keep, commit)
}

const defaultSettings = (): NonNullable<ChatModel["settings"]> => ({
  _tag: "settingsState",
  cliPath: "",
  nodePath: "",
  includeActiveFileByDefault: true,
  useCtrlEnterToSend: false,
  changesPresentation: "toast",
})

const fillSettingsFields = (fields: HTMLElement, model: ChatModel): void => {
  const settings = model.settings ?? defaultSettings()
  fields.replaceChildren()
  const presentation = el("label", "setting")
  presentation.append(el("span", undefined, "Changes"))
  const select = el("select")
  select.title = "Where pending Grok file changes appear"
  select.dataset.action = "setSetting"
  select.dataset.key = "changesPresentation"
  for (
    const [value, label] of [["toast", "Toast + editor review"], [
      "pane",
      "Sidebar split pane",
    ]] as const
  ) {
    const option = el("option", undefined, label)
    option.value = value
    option.selected = settings.changesPresentation === value
    select.append(option)
  }
  presentation.append(select)
  fields.append(presentation)

  const boolRow = (
    key: "useCtrlEnterToSend" | "includeActiveFileByDefault",
    label: string,
    on: boolean,
  ) => {
    const wrap = el("label", "setting setting-check")
    const box = el("input")
    box.type = "checkbox"
    box.checked = on
    box.dataset.action = "setSetting"
    box.dataset.key = key
    wrap.title = label
    wrap.append(box, el("span", undefined, label))
    fields.append(wrap)
  }
  boolRow("useCtrlEnterToSend", "Ctrl/Cmd+Enter to send", settings.useCtrlEnterToSend)
  boolRow(
    "includeActiveFileByDefault",
    "Include the active file by default",
    settings.includeActiveFileByDefault,
  )

  const textRow = (
    key: "cliPath" | "nodePath",
    label: string,
    value: string,
    placeholder: string,
  ) => {
    const wrap = el("label", "setting")
    wrap.append(el("span", undefined, label))
    const input = el("input")
    input.type = "text"
    input.value = value
    input.placeholder = placeholder
    input.title = `${label}. Empty means auto-discover`
    input.dataset.action = "setSetting"
    input.dataset.key = key
    wrap.append(input)
    fields.append(wrap)
  }
  textRow("cliPath", "Grok CLI path", settings.cliPath, "Auto-discover")
  textRow("nodePath", "Node path", settings.nodePath, "Auto-discover")
}

const fillMcpSection = (section: HTMLElement, model: ChatModel): void => {
  const openTools = new Set(
    [...section.querySelectorAll("details.mcp-tools")].flatMap((node) => {
      if (!(node instanceof HTMLDetailsElement) || !node.open) return []
      const name = node.dataset.name
      return name !== undefined && name !== "" ? [name] : []
    }),
  )
  section.replaceChildren()
  const head = el("div", "tools-head")
  head.append(el("span", "tools-title", "MCP servers"))
  const config = el("button", "tools-link", "Grok config")
  config.type = "button"
  config.title = "Open ~/.grok/config.toml"
  config.dataset.action = "openMcpConfig"
  head.append(config)
  section.append(head)
  section.append(el(
    "p",
    "tools-note",
    "Status of every MCP server Grok can see. Uncheck a tool to hide it from Grok; all tools start enabled. Refresh a server to reconnect it in this session. Built-in read/edit/shell/search/web stay on.",
  ))
  const mcp = model.mcp
  if (mcp === undefined || mcp.loading) {
    section.append(el("p", "tools-status", "Checking MCP status…"))
    return
  }
  if (mcp.error !== undefined) {
    section.append(el("p", "tools-error", mcp.error))
  }
  if (mcpNeedsFolderTrust(mcp.servers)) {
    const box = el("div", "mcp-trust")
    box.append(el(
      "p",
      undefined,
      "Grok blocked repo-local MCP in this folder. Trust it to start those servers. This also allows project hooks and LSP.",
    ))
    const trust = el("button", "mcp-trust-btn", "Trust folder")
    trust.type = "button"
    trust.title = "Allow Grok to run project MCP servers and hooks in this folder"
    trust.dataset.action = "trustFolder"
    box.append(trust)
    section.append(box)
  }
  if (mcp.servers.length === 0) {
    section.append(el(
      "p",
      "tools-status",
      "No MCP servers discovered. Add them with grok mcp add or in ~/.grok/config.toml.",
    ))
    return
  }
  section.append(el(
    "p",
    "tools-status",
    `${mcp.healthyCount} healthy · ${mcp.failingCount} failing`,
  ))
  const list = el("ul", "tools-servers")
  for (const server of mcp.servers) {
    const item = el("li", server.healthy ? "mcp-ok" : "mcp-bad")
    const row = el("div", "mcp-row")
    row.append(el("span", "mcp-dot"))
    const names = el("div", "mcp-copy")
    names.append(el("span", "mcp-name", server.name))
    const bits = [
      server.transport,
      server.source,
      server.toolCount !== undefined
        ? server.toolCount === 1 ? "1 tool" : `${server.toolCount} tools`
        : undefined,
      server.healthy ? "healthy" : "failing",
    ].filter((bit): bit is string => bit !== undefined)
    names.append(el("span", "mcp-meta", bits.join(" · ")))
    row.append(names)
    const actions = el("div", "mcp-actions")
    const refresh = el("button", "tools-link", "Refresh")
    refresh.type = "button"
    refresh.title = `Reload ${server.name} in this session`
    refresh.dataset.action = "refreshMcp"
    refresh.dataset.name = server.name
    actions.append(refresh)
    const disabled = server.checks.some((check) => /disabled/i.test(check.label))
    const toggle = el("button", "tools-toggle", disabled ? "Enable" : "Disable")
    toggle.type = "button"
    toggle.title = disabled
      ? `Show ${server.name} to Grok`
      : `Hide ${server.name} from Grok`
    toggle.dataset.action = "setMcpEnabled"
    toggle.dataset.name = server.name
    toggle.dataset.enabled = disabled ? "true" : "false"
    actions.append(toggle)
    row.append(actions)
    item.append(row)
    if (server.checks.length > 0) {
      const checks = el("ul", "mcp-checks")
      for (const check of server.checks) {
        const line = [
          check.passed ? "ok" : "fail",
          check.label,
          check.detail,
          check.hint,
        ].filter((part): part is string => part !== undefined && part !== "").join(" · ")
        checks.append(el("li", check.passed ? "check-ok" : "check-bad", line))
      }
      item.append(checks)
    }
    if (server.tools !== undefined && server.tools.length > 0) {
      const box = el("details", "mcp-tools")
      box.dataset.name = server.name
      if (openTools.has(server.name)) box.open = true
      const toolSummary = el("summary", undefined, mcpToolSummary(server.tools))
      toolSummary.title = "Show tools Grok can call on this server"
      box.append(toolSummary)
      const tools = el("ul", "mcp-tool-list")
      for (const tool of server.tools) {
        const row = el("li", tool.enabled ? undefined : "mcp-tool-off")
        const label = el("label", "mcp-tool")
        const input = el("input")
        input.type = "checkbox"
        input.checked = tool.enabled
        input.dataset.action = "setMcpToolEnabled"
        input.dataset.name = server.name
        input.dataset.tool = tool.name
        label.append(input, el("span", "mcp-tool-name", tool.name))
        label.title = tool.description !== undefined && tool.description !== ""
          ? tool.description
          : tool.enabled
          ? `Hide ${tool.name} from Grok`
          : `Show ${tool.name} to Grok`
        row.append(label)
        tools.append(row)
      }
      box.append(tools)
      item.append(box)
    }
    list.append(item)
  }
  section.append(list)
}

const renderSettingsPanel = (model: ChatModel): HTMLElement => {
  const panel = el("div", "settings-panel")
  const head = el("div", "tools-head")
  head.append(el("span", "tools-title", "Settings"))
  const json = el("button", "json-btn", "{ }")
  json.type = "button"
  json.title = "Open settings JSON"
  json.setAttribute("aria-label", "Open settings JSON")
  json.dataset.action = "openSettingsJson"
  head.append(json)
  panel.append(head)
  const fields = el("div", "settings-fields")
  fillSettingsFields(fields, model)
  panel.append(fields)
  const mcp = el("div", "mcp-section")
  fillMcpSection(mcp, model)
  panel.append(mcp)
  return panel
}

const upsertSettingsPanel = (popovers: HTMLElement, model: ChatModel): void => {
  const existing = popovers.querySelector(".settings-panel")
  if (!(existing instanceof HTMLElement)) {
    popovers.replaceChildren()
    popovers.append(renderSettingsPanel(model))
    return
  }
  const fields = existing.querySelector(".settings-fields")
  if (fields instanceof HTMLElement && !fields.contains(document.activeElement)) {
    fillSettingsFields(fields, model)
  }
  const mcp = existing.querySelector(".mcp-section")
  if (mcp instanceof HTMLElement) fillMcpSection(mcp, model)
}

const renderBar = (shell: ChatShell, state: RenderState): void => {
  const session = state.model.session
  const modeId = session?.modeId !== undefined && session.modeId !== "" ? session.modeId : "normal"
  const modes = sessionModes(session)
  const models = session?.availableModels ?? []
  const modelId = session?.modelId
  const showModel = models.length > 0 || (modelId !== undefined && modelId !== "")
  shell.composer.dataset.mode = modeId

  const left = el("div", "composer-left")
  left.append(
    iconButton("icon-btn", "attach", "Mention a file with @", PLUS_PATH, { stroke: true }),
  )
  if (session?.occupancy !== undefined && session.occupancy.size > 0) {
    const tone = occupancyTone(session.occupancy.used, session.occupancy.size)
    const meter = el("span", `occupancy occupancy-${tone}`)
    meter.title = `Context used this session: ${
      occupancyLabel(session.occupancy.used, session.occupancy.size)
    }`
    const track = el("span", "occupancy-track")
    const fill = el("span", "occupancy-fill")
    fill.style.width = `${occupancyPercent(session.occupancy.used, session.occupancy.size)}%`
    track.append(fill)
    meter.append(
      track,
      el(
        "span",
        "occupancy-copy",
        occupancyLabel(
          session.occupancy.used,
          session.occupancy.size,
        ),
      ),
    )
    left.append(meter)
  }

  const right = el("div", "composer-right")
  const modeBtn = chipSelect(
    modeLabel(modeId, modes),
    "toggleModeMenu",
    state.openMenu === "mode",
    "Mode",
  )
  modeBtn.title = `Mode: ${modeLabel(modeId, modes)}. ${modeTip(modeId)}`
  right.append(modeBtn)
  if (modeId === "plan") {
    const open = el("button", "plan-open-link", "Open plan")
    open.type = "button"
    open.title = "Open plan.md in Markdown Preview"
    open.dataset.action = "openPlan"
    right.append(open)
  }
  if (showModel) {
    const modelBtn = chipSelect(
      modelChipLabel(modelId, models, session?.reasoning),
      "toggleModelMenu",
      state.openMenu === "model",
      "Model",
    )
    modelBtn.title = `Model: ${
      modelChipLabel(modelId, models, session?.reasoning)
    }. Change model and reasoning`
    right.append(modelBtn)
  }
  const failing = state.model.mcp !== undefined && state.model.mcp.failingCount > 0
  const gear = iconButton(
    failing ? "icon-btn tools-warn" : "icon-btn",
    "toggleSettingsMenu",
    failing ? "Settings and MCP servers (some failing)" : "Settings and MCP servers",
    GEAR_PATH,
  )
  if (state.openMenu === "settings") gear.setAttribute("aria-expanded", "true")
  right.append(gear)
  if (turnIsRunning(state.model)) {
    right.append(iconButton("send-btn stop-btn", "cancel", "Stop (Esc)", STOP_PATH))
  } else {
    const sendTip = `Send (${sendShortcut(state.ctrlEnterToSend)})`
    const send = iconButton("send-btn", "send", sendTip, SEND_PATH)
    send.disabled = state.draft.trim() === "" && state.chips.length === 0
    right.append(send)
  }

  shell.bar.replaceChildren(left, right)
}

const contextFromEditor = (
  editor: NonNullable<ChatModel["editor"]>,
): EditorContextView | undefined => {
  if (editor.path === undefined || editor.path === "") return undefined
  const startLine = editor.startLine ?? 1
  return {
    path: editor.path,
    startLine,
    startCol: editor.startCol ?? 1,
    endLine: editor.endLine ?? startLine,
    endCol: editor.endCol ?? (editor.startCol ?? 1),
    hasSelection: editor.hasSelection,
    hasRange: editor.hasSelection && editor.startLine !== undefined,
    ...(editor.excerpt !== undefined && editor.excerpt !== "" ? { excerpt: editor.excerpt } : {}),
  }
}

const contextRow = (
  kind: string,
  text: string,
  title: string,
  action: string,
  className?: string,
): HTMLButtonElement => {
  const row = el(
    "button",
    className !== undefined ? `editor-context ${className}` : "editor-context",
  )
  row.type = "button"
  row.dataset.action = action
  row.title = title
  row.setAttribute("aria-label", title)
  const copy = el("span", "chip-copy")
  copy.append(el("span", "chip-kind", kind), el("span", "editor-path", text))
  row.append(copy)
  return row
}

const renderEditorContext = (shell: ChatShell, state: RenderState): void => {
  const ctx = state.model.editor !== undefined
    ? contextFromEditor(state.model.editor)
    : undefined
  shell.context.replaceChildren()
  if (ctx === undefined) {
    shell.context.hidden = true
    return
  }
  shell.context.hidden = false
  if (ctx.hasSelection && !selectionAlreadyChipped(ctx, state.chips)) {
    const range = editorSelectionLabel(ctx)
    shell.context.append(contextRow(
      "Add",
      `${ctx.path}  ${range}`,
      `Add selection to chat (${addSelectionShortcut()}): ${editorContextLabel(ctx)}`,
      "addSelection",
      "add-selection",
    ))
    return
  }
  const fileText = ctx.hasSelection ? ctx.path : `${ctx.path}:${editorCaretLabel(ctx)}`
  shell.context.append(
    contextRow("File", fileText, `Show ${fileText} in the editor`, "revealEditor"),
  )
}

export const renderChat = (
  shell: ChatShell,
  state: RenderState,
  options: { readonly syncDraft?: boolean } = {},
): void => {
  const nearBottom =
    shell.transcript.scrollHeight - shell.transcript.scrollTop - shell.transcript.clientHeight < 64
  const previousThoughtOpen = thoughtOpenTurns(shell.transcript)
  const previousToolOpen = toolOpenIds(shell.transcript)
  const previousRollupOpen = rollupOpenTurns(shell.transcript)
  const emptyNow = state.model.turns.length === 0
  const emptyShown = shell.transcript.querySelector(":scope > .empty") !== null

  if (!(emptyNow && emptyShown)) {
    shell.transcript.replaceChildren()
    if (emptyNow) {
      shell.transcript.append(renderEmpty())
    } else {
      for (const turn of state.model.turns) {
        shell.transcript.append(
          renderTurn(
            turn,
            previousThoughtOpen.has(turn.id),
            previousToolOpen,
            previousRollupOpen.has(turn.id),
          ),
        )
      }
    }
  }
  renderCards(shell.cards, state.model)
  renderBar(shell, state)
  renderEditorContext(shell, state)
  renderToast(shell.toast, state.model)

  shell.status.replaceChildren()
  if (state.model.error !== undefined) {
    shell.status.append(el("div", "error", state.model.error.message))
  }
  if (state.model.queued > 0) {
    const queued = el("div", "queued")
    const count = state.model.queued
    queued.append(el(
      "span",
      undefined,
      count === 1 ? "1 queued" : `${count} queued`,
    ))
    const sendNow = el("button", "queued-now", "Send now")
    sendNow.type = "button"
    sendNow.title = "Send the next queued message immediately"
    sendNow.dataset.action = "sendNow"
    queued.append(sendNow)
    shell.status.append(queued)
  }

  const slashQuery = slashQueryFromDraft(state.draft)
  if (
    state.openMenu === "settings" && slashQuery === undefined
    && !mentionPopoverOpen(state.draft, state.mentionsDismissed === true)
  ) {
    upsertSettingsPanel(shell.popovers, state.model)
  } else {
    shell.popovers.replaceChildren()
  }
  if (slashQuery !== undefined) {
    const matches = filterSlashCommands(state.model.commands, slashQuery)
    if (matches.length > 0) {
      const popover = el("ul", "slash")
      for (const command of matches) {
        const item = el("li", undefined, `/${command.name} ${command.description}`)
        item.title = command.hint !== undefined && command.hint !== ""
          ? `/${command.name}: ${command.description} (${command.hint})`
          : `/${command.name}: ${command.description}`
        item.dataset.action = "slashPick"
        item.dataset.name = command.name
        popover.append(item)
      }
      shell.popovers.append(popover)
    }
  } else if (mentionPopoverOpen(state.draft, state.mentionsDismissed === true)) {
    const query = mentionQueryFromDraft(state.draft) ?? ""
    const files = mentionChoices(
      state.draft,
      state.model.mentionQuery,
      state.model.mentionFiles,
      state.mentionsDismissed === true,
    )
    const popover = el("ul", "mentions")
    popover.setAttribute("role", "listbox")
    popover.title = "↑↓ to choose, Enter to add"
    if (files.length === 0) {
      const hint = el("li", "mentions-empty")
      hint.textContent = query === ""
        ? "Type to search files"
        : state.model.mentionQuery === query
        ? "No matching files"
        : "Type to search files"
      popover.append(hint)
    } else {
      const selected = state.mentionIndex ?? 0
      files.forEach((file, index) => {
        const item = el("li", index === selected ? "selected" : undefined, file.path)
        item.title = `Attach ${file.path} to the next message`
        item.dataset.action = "mentionPick"
        item.dataset.path = file.path
        item.dataset.absPath = file.absPath
        item.setAttribute("role", "option")
        item.setAttribute("aria-selected", index === selected ? "true" : "false")
        popover.append(item)
      })
    }
    shell.popovers.append(popover)
  } else if (state.openMenu === "mode") {
    const modes = sessionModes(state.model.session)
    const current = state.model.session?.modeId !== undefined && state.model.session.modeId !== ""
      ? state.model.session.modeId
      : "normal"
    shell.popovers.append(menuList(modes.map((mode) => ({
      id: mode.id,
      label: mode.name,
      selected: mode.id === current,
      action: "setMode",
      key: "modeId",
      title: modeTip(mode.id),
    }))))
  } else if (state.openMenu === "model") {
    shell.popovers.append(renderModelMenu(state))
  }

  shell.chips.replaceChildren()
  state.chips.forEach((chip, index) => {
    const label = chip.startLine !== undefined && chip.endLine !== undefined
      ? `@${chip.path}:${chip.startLine}-${chip.endLine}`
      : `@${chip.path}`
    const node = el("span", "chip")
    const open = el("button", "chip-open", label)
    open.type = "button"
    open.dataset.action = "revealEditor"
    open.dataset.absPath = chip.absPath
    if (chip.startLine !== undefined) open.dataset.startLine = String(chip.startLine)
    if (chip.endLine !== undefined) open.dataset.endLine = String(chip.endLine)
    open.title = `Show ${label} in the editor`
    open.setAttribute("aria-label", `Show ${label} in the editor`)
    const close = el("button", "chip-remove", "×")
    close.type = "button"
    close.dataset.action = "removeChip"
    close.dataset.index = String(index)
    close.title = `Remove ${label} from chat`
    close.setAttribute("aria-label", `Remove ${label} from chat`)
    node.append(open, close)
    shell.chips.append(node)
  })
  shell.chips.hidden = state.chips.length === 0

  shell.draft.title = state.ctrlEnterToSend
    ? `Message Grok. ${sendShortcut(true)} sends, Enter inserts a newline`
    : `Message Grok. ${sendShortcut(false)} sends, Shift+Enter inserts a newline`
  if (options.syncDraft === true || document.activeElement !== shell.draft) {
    shell.draft.value = state.draft
  }
  resizeDraft(shell.draft)

  if (nearBottom) shell.transcript.scrollTop = shell.transcript.scrollHeight
}
