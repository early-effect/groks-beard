import { BEARD_THEME_CSS } from "./beard-theme.js"

const attr = (value: string): string =>
  value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;")

export const changesReviewCss = `
${BEARD_THEME_CSS.trim()}
:root {
  color: var(--vscode-foreground);
  background: var(--vscode-editor-background);
  font-family: var(--vscode-font-family);
  font-size: var(--vscode-font-size);
  accent-color: var(--beard-orange);
}
html, body, #root {
  height: 100%;
  margin: 0;
}
#root {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  box-sizing: border-box;
}
.header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px 10px;
  border-bottom: 1px solid var(--vscode-widget-border, transparent);
}
.header h1 {
  margin: 0;
  flex: 1;
  font-size: 16px;
  font-weight: 600;
}
.actions {
  display: flex;
  gap: 6px;
}
button {
  appearance: none;
  font: inherit;
  cursor: pointer;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
}
.primary {
  background: var(--beard-orange);
  color: var(--beard-cream);
  border: none;
}
.secondary {
  background: var(--vscode-button-secondaryBackground, transparent);
  color: var(--vscode-button-secondaryForeground, var(--vscode-foreground));
  border: 1px solid var(--vscode-widget-border, transparent);
}
button:hover {
  filter: brightness(1.08);
}
button:disabled {
  opacity: 0.45;
  cursor: default;
  filter: none;
}
.empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--vscode-descriptionForeground);
  padding: 24px;
}
.turns {
  flex: 1;
  overflow: auto;
  padding: 8px 12px 16px;
}
.turn {
  margin-top: 12px;
}
.turn-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
}
.turn-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stats {
  display: inline-flex;
  gap: 4px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.stats-add {
  color: var(--vscode-gitDecoration-addedResourceForeground, #3fb950);
}
.stats-del {
  color: var(--vscode-gitDecoration-deletedResourceForeground, #f85149);
}
.file-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 2px;
}
.file {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  margin: 0;
  padding: 6px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
}
.file:hover {
  background: var(--vscode-list-hoverBackground);
}
.file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kind {
  color: var(--vscode-descriptionForeground);
  font-size: 11px;
}
`

export const changesReviewHtml = (input: {
  readonly cspSource: string
  readonly nonce: string
}): string => {
  const csp = [
    "default-src 'none'",
    `script-src 'nonce-${input.nonce}'`,
    `style-src ${input.cspSource} 'unsafe-inline'`,
    "img-src data:",
    "connect-src 'none'",
  ].join("; ")
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="${csp}" />
  <style>${changesReviewCss}</style>
</head>
<body>
  <div id="root"></div>
  <script nonce="${attr(input.nonce)}">${reviewScript}</script>
</body>
</html>`
}

const reviewScript = `
const vscode = acquireVsCodeApi()
const root = document.getElementById("root")
const el = (tag, className, text) => {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text !== undefined) node.textContent = text
  return node
}
const fileLabel = (count) => count === 1 ? "1 file" : count + " files"
const stats = (additions, deletions) => {
  const wrap = el("span", "stats")
  wrap.append(
    el("span", "stats-add", "+" + additions),
    el("span", "stats-del", "\\u2212" + deletions),
  )
  return wrap
}
const render = (state) => {
  root.replaceChildren()
  const turns = state.turns || []
  const files = turns.reduce((n, turn) => n + turn.files.length, 0)
  const header = el("div", "header")
  header.append(el("h1", undefined, files === 0 ? "Grok Changes" : "Grok Changes · " + fileLabel(files)))
  const actions = el("div", "actions")
  const keep = el("button", "primary", "Keep all")
  keep.dataset.act = "keepEvery"
  keep.disabled = files === 0
  const undo = el("button", "secondary", "Undo all")
  undo.dataset.act = "undoEvery"
  undo.disabled = files === 0 || !state.canUndoEvery
  const commit = el("button", "primary", "Commit")
  commit.dataset.act = "commitEvery"
  commit.disabled = files === 0
  actions.append(keep, undo, commit)
  header.append(actions)
  root.append(header)
  if (files === 0) {
    root.append(el("div", "empty", "No pending changes."))
    return
  }
  const list = el("div", "turns")
  for (const turn of turns) {
    const section = el("section", "turn")
    const head = el("div", "turn-head")
    head.append(el("div", "turn-title", turn.title || turn.turnId))
    head.append(stats(turn.additions, turn.deletions))
    const keepTurn = el("button", "secondary", "Keep")
    keepTurn.dataset.act = "keepTurn"
    keepTurn.dataset.sessionId = turn.sessionId
    keepTurn.dataset.turnId = turn.turnId
    const undoTurn = el("button", "secondary", "Undo")
    undoTurn.dataset.act = "undoTurn"
    undoTurn.dataset.sessionId = turn.sessionId
    undoTurn.dataset.turnId = turn.turnId
    undoTurn.disabled = !turn.canUndo
    const commitTurn = el("button", "secondary", "Commit")
    commitTurn.dataset.act = "commitTurn"
    commitTurn.dataset.sessionId = turn.sessionId
    commitTurn.dataset.turnId = turn.turnId
    head.append(keepTurn, undoTurn, commitTurn)
    section.append(head)
    for (const file of turn.files) {
      const row = el("div", "file-row")
      const open = el("button", "file")
      open.dataset.act = "open"
      open.dataset.sessionId = turn.sessionId
      open.dataset.turnId = turn.turnId
      open.dataset.path = file.path
      open.append(el("span", "file-name", file.name))
      open.append(el("span", "kind", file.kind))
      open.append(stats(file.additions, file.deletions))
      const keepFile = el("button", "secondary", "Keep")
      keepFile.dataset.act = "keep"
      keepFile.dataset.sessionId = turn.sessionId
      keepFile.dataset.turnId = turn.turnId
      keepFile.dataset.path = file.path
      const undoFile = el("button", "secondary", "Undo")
      undoFile.dataset.act = "undo"
      undoFile.dataset.sessionId = turn.sessionId
      undoFile.dataset.turnId = turn.turnId
      undoFile.dataset.path = file.path
      undoFile.disabled = !file.canUndo
      const commitFile = el("button", "secondary", "Commit")
      commitFile.dataset.act = "commit"
      commitFile.dataset.sessionId = turn.sessionId
      commitFile.dataset.turnId = turn.turnId
      commitFile.dataset.path = file.path
      row.append(open, keepFile, undoFile, commitFile)
      section.append(row)
    }
    list.append(section)
  }
  root.append(list)
}
window.addEventListener("message", (event) => render(event.data))
root.addEventListener("click", (event) => {
  const target = event.target.closest("[data-act]")
  if (!target) return
  vscode.postMessage({
    act: target.dataset.act,
    sessionId: target.dataset.sessionId,
    turnId: target.dataset.turnId,
    path: target.dataset.path
  })
})
vscode.postMessage({ act: "ready" })
`
