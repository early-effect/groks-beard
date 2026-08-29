export const THOUGHT_HEADLINE_MAX = 72

export const thoughtHeadline = (full: string): string => {
  const line = full.split(/\r?\n/).find((row) => row.trim() !== "")?.trim() ?? ""
  if (line.length <= THOUGHT_HEADLINE_MAX) return line
  return `${line.slice(0, THOUGHT_HEADLINE_MAX)}...`
}

export const thoughtSummaryLabel = (full: string, done: boolean): string => {
  const title = done ? "Thought" : "Thinking"
  const headline = thoughtHeadline(full)
  return headline === "" ? title : `${title}: ${headline}`
}

export type ThoughtBlock = {
  readonly tag: "details"
  readonly open: boolean
  readonly summary: string
  readonly stream: string
}

export const thoughtBlock = (
  full: string,
  done: boolean,
  open: boolean,
): ThoughtBlock | undefined => {
  if (full.length === 0) return undefined
  return {
    tag: "details",
    open,
    summary: thoughtSummaryLabel(full, done),
    stream: full,
  }
}
