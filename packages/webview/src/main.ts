import { decodeHostMsg, type HostMsg, type WebviewMsg } from "@groks-beard/core"
import { mentionChoices, mentionQueryFromDraft, moveMentionIndex } from "./mentions.js"
import { applyHostMsg, emptyChatModel, turnIsRunning } from "./model.js"
import { type ComposerChip, mountShell, type OpenMenu, renderChat } from "./render.js"

type VsCodeApi = {
  readonly postMessage: (message: unknown) => void
}

declare function acquireVsCodeApi(): VsCodeApi

const vscode = acquireVsCodeApi()
const root = document.getElementById("root")
if (root === null) throw new Error("chat root missing")

const ctrlEnterToSend = document.body.dataset.ctrlEnter === "true"
const shell = mountShell(root, ctrlEnterToSend)

let model = emptyChatModel()
let draft = ""
let openMenu: OpenMenu
let openModelSettings: string | undefined
let mentionsDismissed = false
let mentionIndex: number | undefined
let mentionTimer: ReturnType<typeof setTimeout> | undefined
const chips: Array<ComposerChip> = []
const pending: Array<HostMsg> = []
let frame = 0

const post = (message: WebviewMsg): void => {
  vscode.postMessage(message)
}

const paint = (syncDraft = false): void => {
  renderChat(shell, {
    model,
    draft,
    ctrlEnterToSend,
    chips,
    openMenu,
    ...(openModelSettings !== undefined ? { openModelSettings } : {}),
    ...(mentionsDismissed ? { mentionsDismissed } : {}),
    ...(mentionIndex !== undefined ? { mentionIndex } : {}),
  }, { syncDraft })
  const selected = shell.popovers.querySelector(".mentions li.selected")
  if (selected instanceof HTMLElement) {
    selected.scrollIntoView({ block: "nearest" })
  }
}

const flush = (): void => {
  frame = 0
  const batch = pending.splice(0)
  for (const msg of batch) model = applyHostMsg(model, msg)
  paint()
}

const enqueue = (msg: HostMsg): void => {
  pending.push(msg)
  if (frame !== 0) return
  const raf = globalThis.requestAnimationFrame
  frame = typeof raf === "function" ? raf(flush) : (flush(), 1)
}

const upsertChip = (chip: ComposerChip): void => {
  const existing = chips.findIndex((row) =>
    row.absPath === chip.absPath && row.startLine === chip.startLine && row.endLine === chip.endLine
  )
  if (existing >= 0) chips.splice(existing, 1, chip)
  else chips.push(chip)
}

window.addEventListener("message", (event: MessageEvent<unknown>) => {
  try {
    const msg = decodeHostMsg(event.data)
    if (msg._tag === "composerChip") {
      upsertChip({
        path: msg.path,
        absPath: msg.absPath,
        source: msg.source,
        ...(msg.startLine !== undefined ? { startLine: msg.startLine } : {}),
        ...(msg.endLine !== undefined ? { endLine: msg.endLine } : {}),
        ...(msg.languageId !== undefined ? { languageId: msg.languageId } : {}),
        ...(msg.excerpt !== undefined && msg.excerpt !== "" ? { excerpt: msg.excerpt } : {}),
      })
      paint()
      return
    }
    enqueue(msg)
  } catch {
    return
  }
})

const mentionFiles = () =>
  mentionChoices(
    draft,
    model.mentionQuery,
    model.mentionFiles,
    mentionsDismissed,
  )

const pickMention = (path: string, absPath: string): void => {
  chips.push({ path, absPath, source: "mention" })
  draft = draft.replace(/(?:^|\s)@[^\s]*$/, (chunk) => chunk.startsWith(" ") ? " " : "")
    .trimEnd()
  mentionsDismissed = false
  mentionIndex = undefined
  post({ _tag: "mentionPick", path, absPath })
  post({ _tag: "mentionQuery", query: "" })
  paint(true)
}

const sendDraft = (tag: "send" | "queue" | "steer"): void => {
  const text = draft.trim()
  if (text === "" && chips.length === 0) return
  post({
    _tag: tag,
    text: draft,
    chips: chips.map((chip) => ({
      _tag: "PromptChip" as const,
      path: chip.path,
      absPath: chip.absPath,
      source: chip.source,
      ...(chip.startLine !== undefined ? { startLine: chip.startLine } : {}),
      ...(chip.endLine !== undefined ? { endLine: chip.endLine } : {}),
      ...(chip.languageId !== undefined ? { languageId: chip.languageId } : {}),
      ...(chip.excerpt !== undefined && chip.excerpt !== "" ? { excerpt: chip.excerpt } : {}),
    })),
  })
  draft = ""
  chips.splice(0)
  paint(true)
}

root.addEventListener("change", (event) => {
  const raw = event.target
  if (!(raw instanceof HTMLInputElement) || raw.dataset.action !== "setMcpToolEnabled") return
  if (raw.dataset.name === undefined || raw.dataset.tool === undefined) return
  post({
    _tag: "setMcpToolEnabled",
    name: raw.dataset.name,
    tool: raw.dataset.tool,
    enabled: raw.checked,
  })
})

root.addEventListener("click", (event) => {
  const raw = event.target
  if (!(raw instanceof Element)) return
  const target = raw.closest("[data-action]")
  if (!(target instanceof HTMLElement)) {
    if (raw.closest(".settings-panel, .tools-panel, .menu, .slash, .mentions") !== null) {
      return
    }
    if (raw === shell.draft) {
      return
    }
    if (openMenu !== undefined) {
      openMenu = undefined
      openModelSettings = undefined
      paint()
    }
    if (mentionQueryFromDraft(draft) !== undefined && !mentionsDismissed) {
      mentionsDismissed = true
      mentionIndex = undefined
      paint()
    }
    return
  }
  const action = target.dataset.action
  if (action === undefined) return
  const requestId = target.closest("[data-request-id]")?.getAttribute("data-request-id") ?? ""
  switch (action) {
    case "send":
      openMenu = undefined
      openModelSettings = undefined
      sendDraft("send")
      return
    case "cancel":
      openMenu = undefined
      openModelSettings = undefined
      post({ _tag: "cancel" })
      return
    case "cycleMode":
      post({ _tag: "cycleMode" })
      return
    case "toggleModeMenu":
      openMenu = openMenu === "mode" ? undefined : "mode"
      openModelSettings = undefined
      paint()
      return
    case "toggleModelMenu":
      openMenu = openMenu === "model" ? undefined : "model"
      openModelSettings = undefined
      paint()
      return
    case "toggleModelReasoning":
      if (target.dataset.modelId !== undefined && target.dataset.modelId !== "") {
        openMenu = "model"
        openModelSettings = openModelSettings === target.dataset.modelId
          ? undefined
          : target.dataset.modelId
        paint()
      }
      return
    case "toggleSettingsMenu":
      openMenu = openMenu === "settings" ? undefined : "settings"
      openModelSettings = undefined
      if (openMenu === "settings") post({ _tag: "openSettings" })
      paint()
      return
    case "refreshMcp":
      if (target.dataset.name !== undefined && target.dataset.name !== "") {
        post({ _tag: "refreshMcp", name: target.dataset.name })
      }
      return
    case "openMcpConfig":
      post({ _tag: "openMcpConfig" })
      return
    case "trustFolder":
      post({ _tag: "trustFolder" })
      return
    case "setMcpEnabled":
      if (target.dataset.name !== undefined) {
        post({
          _tag: "setMcpEnabled",
          name: target.dataset.name,
          enabled: target.dataset.enabled !== "false",
        })
      }
      return
    case "setMcpToolEnabled":
      return
    case "setMode":
      if (target.dataset.modeId !== undefined) {
        openMenu = undefined
        openModelSettings = undefined
        post({ _tag: "setMode", modeId: target.dataset.modeId })
        paint()
      }
      return
    case "setModel":
      if (target.dataset.modelId !== undefined) {
        openMenu = undefined
        openModelSettings = undefined
        post({ _tag: "setModel", modelId: target.dataset.modelId })
        paint()
      }
      return
    case "setReasoning":
      if (target.dataset.value !== undefined && target.dataset.value !== "") {
        post({
          _tag: "setReasoning",
          value: target.dataset.value,
          ...(target.dataset.modelId !== undefined && target.dataset.modelId !== ""
            ? { modelId: target.dataset.modelId }
            : {}),
        })
      }
      return
    case "sendNow":
      post({ _tag: "sendNow" })
      return
    case "openSettingsJson":
      post({ _tag: "openSettingsJson" })
      return
    case "openChanges":
      openMenu = undefined
      post({ _tag: "openChanges" })
      return
    case "keepAllPending":
      openMenu = undefined
      post({ _tag: "keepAllPending" })
      return
    case "commitAllPending":
      openMenu = undefined
      post({ _tag: "commitAllPending" })
      return
    case "attach":
      openMenu = undefined
      mentionsDismissed = false
      mentionIndex = 0
      if (mentionQueryFromDraft(draft) === undefined) {
        draft = draft === "" || draft.endsWith(" ") ? `${draft}@` : `${draft} @`
      }
      post({ _tag: "mentionQuery", query: mentionQueryFromDraft(draft) ?? "" })
      paint(true)
      shell.draft.focus()
      return
    case "permissionChoice":
      if (target.dataset.optionId !== undefined) {
        post({ _tag: "permissionChoice", requestId, optionId: target.dataset.optionId })
      }
      return
    case "openDiff":
      post({ _tag: "openDiff", requestId })
      return
    case "addSelection":
      post({ _tag: "addSelection" })
      return
    case "revealEditor": {
      const startLine = target.dataset.startLine !== undefined
        ? Number(target.dataset.startLine)
        : undefined
      const endLine = target.dataset.endLine !== undefined
        ? Number(target.dataset.endLine)
        : undefined
      post({
        _tag: "revealEditor",
        ...(target.dataset.absPath !== undefined && target.dataset.absPath !== ""
          ? { absPath: target.dataset.absPath }
          : {}),
        ...(startLine !== undefined && Number.isFinite(startLine) ? { startLine } : {}),
        ...(endLine !== undefined && Number.isFinite(endLine) ? { endLine } : {}),
      })
      return
    }
    case "openPlan":
      post({
        _tag: "openPlan",
        ...(model.plan !== undefined && model.plan.planMarkdown !== ""
          ? { markdown: model.plan.planMarkdown }
          : {}),
      })
      return
    case "planVerdict":
      if (
        target.dataset.verdict === "approved"
        || target.dataset.verdict === "cancelled"
        || target.dataset.verdict === "abandoned"
      ) {
        post({ _tag: "planVerdict", requestId, verdict: target.dataset.verdict })
      }
      return
    case "questionDismiss":
      post({ _tag: "questionDismiss", requestId })
      return
    case "elicitAccept":
      post({ _tag: "elicitAccept", requestId })
      return
    case "elicitDecline":
      post({ _tag: "elicitDecline", requestId })
      return
    case "slashPick":
      if (target.dataset.name !== undefined) {
        draft = `/${target.dataset.name} `
        post({ _tag: "slashPick", name: target.dataset.name })
        paint(true)
      }
      return
    case "removeChip":
      if (target.dataset.index !== undefined) {
        const index = Number(target.dataset.index)
        if (Number.isInteger(index) && index >= 0 && index < chips.length) {
          chips.splice(index, 1)
          paint()
        }
      }
      return
    case "mentionPick":
      if (target.dataset.path !== undefined && target.dataset.absPath !== undefined) {
        pickMention(target.dataset.path, target.dataset.absPath)
      }
      return
    default:
      return
  }
})

root.addEventListener("change", (event) => {
  const target = event.target
  if (!(target instanceof HTMLInputElement || target instanceof HTMLSelectElement)) return
  if (target.dataset.action === "setReasoning") {
    post({
      _tag: "setReasoning",
      value: target.value,
      ...(target.dataset.modelId !== undefined && target.dataset.modelId !== ""
        ? { modelId: target.dataset.modelId }
        : {}),
    })
    return
  }
  if (target.dataset.action !== "setSetting" || target.dataset.key === undefined) return
  const key = target.dataset.key
  if (
    key !== "cliPath"
    && key !== "nodePath"
    && key !== "includeActiveFileByDefault"
    && key !== "useCtrlEnterToSend"
    && key !== "changesPresentation"
  ) {
    return
  }
  if (target instanceof HTMLInputElement && target.type === "checkbox") {
    post({ _tag: "setSetting", key, value: target.checked })
    return
  }
  post({ _tag: "setSetting", key, value: target.value })
})

shell.draft.addEventListener("input", () => {
  draft = shell.draft.value
  if (openMenu !== undefined) {
    openMenu = undefined
    openModelSettings = undefined
  }
  const query = mentionQueryFromDraft(draft)
  mentionIndex = 0
  if (query === undefined) {
    mentionsDismissed = false
    mentionIndex = undefined
    if (mentionTimer !== undefined) {
      globalThis.clearTimeout(mentionTimer)
      mentionTimer = undefined
    }
    paint()
    return
  }
  mentionsDismissed = false
  if (mentionTimer !== undefined) globalThis.clearTimeout(mentionTimer)
  mentionTimer = globalThis.setTimeout(() => {
    mentionTimer = undefined
    post({ _tag: "mentionQuery", query })
  }, 80)
  paint()
})

shell.draft.addEventListener("focus", () => {
  if (openMenu === undefined) return
  openMenu = undefined
  openModelSettings = undefined
  paint()
})

window.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    event.preventDefault()
    if (openMenu !== undefined) {
      openMenu = undefined
      paint()
      return
    }
    if (mentionQueryFromDraft(draft) !== undefined && !mentionsDismissed) {
      mentionsDismissed = true
      mentionIndex = undefined
      paint()
      return
    }
    if (model.permission !== undefined) {
      post({ _tag: "permissionPark", requestId: model.permission.requestId })
      return
    }
    if (turnIsRunning(model)) post({ _tag: "cancel" })
    return
  }
  if (!(event.target instanceof HTMLTextAreaElement)) return
  const files = mentionFiles()
  if (files.length > 0 && (event.key === "ArrowUp" || event.key === "ArrowDown")) {
    event.preventDefault()
    mentionIndex = moveMentionIndex(mentionIndex, event.key, files.length)
    paint()
    return
  }
  if (
    files.length > 0
    && (event.key === "Enter" || event.key === "Tab")
    && !event.shiftKey
    && !event.metaKey
    && !event.ctrlKey
  ) {
    const pick = files[mentionIndex ?? 0]
    if (pick !== undefined) {
      event.preventDefault()
      pickMention(pick.path, pick.absPath)
      return
    }
  }
  const empty = draft.trim() === "" && chips.length === 0
  if (event.key === "Enter" && !event.shiftKey && empty && model.queued > 0) {
    event.preventDefault()
    post({ _tag: "sendNow" })
    return
  }
  const metaSend = (event.ctrlKey || event.metaKey) && event.key === "Enter"
  const enterSend = !ctrlEnterToSend && event.key === "Enter" && !event.shiftKey
  if (metaSend || enterSend) {
    event.preventDefault()
    sendDraft("send")
  }
})

post({ _tag: "ready" })
paint()
