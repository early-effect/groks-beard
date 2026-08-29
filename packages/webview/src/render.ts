import { formatAtRef, type PromptChip } from "@groks-beard/core"
import { renderMarkdown } from "./markdown.js"
import type { ChatModel, TurnView } from "./model.js"
import { filterSlashCommands, slashQueryFromDraft } from "./slash.js"
import { thoughtBlock } from "./thinking.js"

export type ComposerChip = {
  readonly path: string
  readonly absPath: string
  readonly startLine?: number
  readonly endLine?: number
  readonly languageId?: string
  readonly source: PromptChip["source"]
}

export type RenderState = {
  readonly model: ChatModel
  readonly draft: string
  readonly ctrlEnterToSend: boolean
  readonly chips: ReadonlyArray<ComposerChip>
}

export type ChatShell = {
  readonly root: HTMLElement
  readonly header: HTMLElement
  readonly transcript: HTMLElement
  readonly cards: HTMLElement
  readonly popovers: HTMLElement
  readonly status: HTMLElement
  readonly chips: HTMLElement
  readonly draft: HTMLTextAreaElement
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

export const mountShell = (root: HTMLElement, ctrlEnterToSend: boolean): ChatShell => {
  const existing = root.querySelector("#transcript")
  if (existing !== null) {
    return {
      root,
      header: root.querySelector("header.meta") as HTMLElement,
      transcript: existing as HTMLElement,
      cards: root.querySelector(".cards") as HTMLElement,
      popovers: root.querySelector(".popovers") as HTMLElement,
      status: root.querySelector(".status") as HTMLElement,
      chips: root.querySelector(".chips") as HTMLElement,
      draft: root.querySelector("#draft") as HTMLTextAreaElement,
    }
  }
  const header = el("header", "meta")
  const transcript = el("div", "transcript")
  transcript.id = "transcript"
  const cards = el("div", "cards")
  const popovers = el("div", "popovers")
  const status = el("div", "status")
  const composer = el("div", "composer")
  const chips = el("div", "chips")
  const draft = el("textarea")
  draft.id = "draft"
  draft.placeholder = ctrlEnterToSend
    ? "Message Grok. Ctrl/Cmd+Enter to send."
    : "Message Grok. Enter to send."
  const send = el("button", "send", "Send")
  send.dataset.action = "send"
  composer.append(chips, draft, send)
  root.append(header, transcript, cards, popovers, status, composer)
  return { root, header, transcript, cards, popovers, status, chips, draft }
}

const thoughtOpenTurns = (transcript: HTMLElement): Set<string> => {
  const open = new Set<string>()
  for (const details of transcript.querySelectorAll("details.thought[open]")) {
    const turnId = details.getAttribute("data-turn")
    if (turnId !== null) open.add(turnId)
  }
  return open
}

const renderTurn = (turn: TurnView, wasOpen: boolean): HTMLElement => {
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
    const pre = el("pre", "thought-stream")
    pre.textContent = block.stream
    details.append(summary, pre)
    section.append(details)
  }
  if (turn.agent.length > 0) {
    const agent = el("div", "agent")
    agent.innerHTML = renderMarkdown(turn.agent)
    section.append(agent)
  }
  if (turn.tools.length > 0) {
    const list = el("ul", "tools")
    for (const tool of turn.tools) {
      list.append(el("li", undefined, `${tool.title} (${tool.status})`))
    }
    section.append(list)
  }
  if (turn.stopReason !== undefined && turn.stopReason !== "end_turn") {
    section.append(el("div", "stop", turn.stopReason))
  }
  return section
}

const renderCards = (cards: HTMLElement, model: ChatModel): void => {
  cards.replaceChildren()
  if (model.permission !== undefined) {
    const card = el("div", "card permission")
    card.dataset.requestId = model.permission.requestId
    card.append(el("h3", undefined, model.permission.title))
    model.permission.options.forEach((option, index) => {
      const button = el("button", undefined, `${index + 1} ${option.name}`)
      button.dataset.action = "permissionChoice"
      button.dataset.optionId = option.optionId
      card.append(button)
    })
    if (model.permission.hasDiff) {
      const diff = el("button", undefined, "Open diff")
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
    card.append(pre)
    for (
      const [verdict, label] of [
        ["approved", "Approve"],
        ["cancelled", "Request changes"],
        ["abandoned", "Abandon"],
      ] as const
    ) {
      const button = el("button", undefined, label)
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
    const dismiss = el("button", undefined, "Dismiss")
    dismiss.dataset.action = "questionDismiss"
    card.append(dismiss)
    cards.append(card)
  }
  if (model.elicit !== undefined) {
    const card = el("div", "card elicit")
    card.dataset.requestId = model.elicit.requestId
    card.append(el("h3", undefined, model.elicit.title))
    const accept = el("button", undefined, "Accept")
    accept.dataset.action = "elicitAccept"
    const decline = el("button", undefined, "Decline")
    decline.dataset.action = "elicitDecline"
    card.append(accept, decline)
    cards.append(card)
  }
}

export const renderChat = (
  shell: ChatShell,
  state: RenderState,
  options: { readonly syncDraft?: boolean } = {},
): void => {
  const nearBottom =
    shell.transcript.scrollHeight - shell.transcript.scrollTop - shell.transcript.clientHeight < 64
  const previousThoughtOpen = thoughtOpenTurns(shell.transcript)

  shell.header.replaceChildren()
  const session = state.model.session
  shell.header.append(el(
    "span",
    "mode",
    session?.modeId !== undefined && session.modeId !== "" ? session.modeId : "Grok's Beard",
  ))
  if (session?.occupancy !== undefined) {
    shell.header.append(
      el("span", "occupancy", `${session.occupancy.used}/${session.occupancy.size}`),
    )
  }
  const cycle = el("button", "cycle-mode", "Cycle mode")
  cycle.dataset.action = "cycleMode"
  shell.header.append(cycle)

  shell.transcript.replaceChildren()
  for (const turn of state.model.turns) {
    shell.transcript.append(renderTurn(turn, previousThoughtOpen.has(turn.id)))
  }
  renderCards(shell.cards, state.model)

  shell.status.replaceChildren()
  if (state.model.error !== undefined) {
    shell.status.append(el("div", "error", state.model.error.message))
  }
  if (state.model.queued > 0) {
    shell.status.append(el("div", "queued", `Queued: ${state.model.queued}`))
  }

  shell.popovers.replaceChildren()
  const slashQuery = slashQueryFromDraft(state.draft)
  if (slashQuery !== undefined) {
    const matches = filterSlashCommands(state.model.commands, slashQuery)
    if (matches.length > 0) {
      const popover = el("ul", "slash")
      for (const command of matches) {
        const item = el("li", undefined, `/${command.name} ${command.description}`)
        item.dataset.action = "slashPick"
        item.dataset.name = command.name
        popover.append(item)
      }
      shell.popovers.append(popover)
    }
  }
  if (state.model.mentionFiles.length > 0) {
    const popover = el("ul", "mentions")
    for (const file of state.model.mentionFiles) {
      const item = el("li", undefined, file.path)
      item.dataset.action = "mentionPick"
      item.dataset.path = file.path
      item.dataset.absPath = file.absPath
      popover.append(item)
    }
    shell.popovers.append(popover)
  }

  shell.chips.replaceChildren()
  for (const chip of state.chips) {
    shell.chips.append(el(
      "span",
      "chip",
      chip.startLine !== undefined && chip.endLine !== undefined
        ? `@${chip.path}:${chip.startLine}-${chip.endLine}`
        : `@${chip.path}`,
    ))
  }

  if (options.syncDraft === true || document.activeElement !== shell.draft) {
    shell.draft.value = state.draft
  }

  if (nearBottom) shell.transcript.scrollTop = shell.transcript.scrollHeight
}
