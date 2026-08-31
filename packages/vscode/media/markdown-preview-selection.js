const dataLine = (node) => {
  let el = node
  if (el !== null && el.nodeType !== 1) el = el.parentElement
  while (el instanceof Element) {
    const raw = el.getAttribute("data-line")
    if (raw !== null && raw !== "") {
      const n = Number(raw)
      if (Number.isFinite(n) && n >= 0) return n
    }
    el = el.parentElement
  }
  return undefined
}

const payloadFromSelection = () => {
  const sel = globalThis.getSelection?.()
  if (sel === null || sel === undefined || sel.isCollapsed) return undefined
  const excerpt = sel.toString().trim()
  if (excerpt === "") return undefined
  const anchor = dataLine(sel.anchorNode)
  const focus = dataLine(sel.focusNode)
  const start = anchor !== undefined && focus !== undefined
    ? Math.min(anchor, focus) + 1
    : undefined
  const end = anchor !== undefined && focus !== undefined ? Math.max(anchor, focus) + 1 : undefined
  return {
    excerpt: excerpt.slice(0, 4000),
    ...(start !== undefined ? { startLine: start } : {}),
    ...(end !== undefined ? { endLine: end } : {}),
  }
}

const invokeAddSelection = (payload) => {
  const encoded = encodeURIComponent(JSON.stringify([payload]))
  const a = document.createElement("a")
  a.href = `command:groksBeard.addSelection?${encoded}`
  a.style.display = "none"
  document.body.append(a)
  a.click()
  a.remove()
}

const isAddShortcut = (event) =>
  event.shiftKey && (event.metaKey || event.ctrlKey)
  && (event.key === ";" || event.code === "Semicolon")

document.addEventListener("keydown", (event) => {
  if (!isAddShortcut(event)) return
  const payload = payloadFromSelection()
  if (payload === undefined) return
  event.preventDefault()
  invokeAddSelection(payload)
})
