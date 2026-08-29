import { decodeHostMsg, type HostMsg, type WebviewMsg } from "@groks-beard/core"
import { applyHostMsg, emptyChatModel } from "./model.js"
import { type ComposerChip, mountShell, renderChat } from "./render.js"

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
const chips: Array<ComposerChip> = []
const pending: Array<HostMsg> = []
let frame = 0

const post = (message: WebviewMsg): void => {
  vscode.postMessage(message)
}

const paint = (syncDraft = false): void => {
  renderChat(shell, { model, draft, ctrlEnterToSend, chips }, { syncDraft })
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

window.addEventListener("message", (event: MessageEvent<unknown>) => {
  try {
    enqueue(decodeHostMsg(event.data))
  } catch {
    return
  }
})

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
    })),
  })
  draft = ""
  chips.splice(0)
  paint(true)
}

root.addEventListener("click", (event) => {
  const target = event.target
  if (!(target instanceof HTMLElement)) return
  const action = target.dataset.action
  if (action === undefined) return
  const requestId = target.closest("[data-request-id]")?.getAttribute("data-request-id") ?? ""
  switch (action) {
    case "send":
      sendDraft("send")
      return
    case "cycleMode":
      post({ _tag: "cycleMode" })
      return
    case "permissionChoice":
      if (target.dataset.optionId !== undefined) {
        post({ _tag: "permissionChoice", requestId, optionId: target.dataset.optionId })
      }
      return
    case "openDiff":
      post({ _tag: "openDiff", requestId })
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
    case "mentionPick":
      if (target.dataset.path !== undefined && target.dataset.absPath !== undefined) {
        chips.push({
          path: target.dataset.path,
          absPath: target.dataset.absPath,
          source: "mention",
        })
        post({
          _tag: "mentionPick",
          path: target.dataset.path,
          absPath: target.dataset.absPath,
        })
        paint()
      }
      return
    default:
      return
  }
})

shell.draft.addEventListener("input", () => {
  draft = shell.draft.value
  const mention = draft.match(/(?:^|\s)@([^\s]*)$/)
  if (mention?.[1] !== undefined) {
    post({ _tag: "mentionQuery", query: mention[1] })
  }
  paint()
})

window.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    if (model.permission !== undefined) {
      post({ _tag: "permissionPark", requestId: model.permission.requestId })
      return
    }
    post({ _tag: "cancel" })
    return
  }
  if (!(event.target instanceof HTMLTextAreaElement)) return
  const metaSend = (event.ctrlKey || event.metaKey) && event.key === "Enter"
  const enterSend = !ctrlEnterToSend && event.key === "Enter" && !event.shiftKey
  if (metaSend || enterSend) {
    event.preventDefault()
    sendDraft("send")
  }
})

post({ _tag: "ready" })
paint()
