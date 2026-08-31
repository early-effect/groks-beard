export const mentionQueryFromDraft = (draft: string): string | undefined => {
  const match = draft.match(/(?:^|\s)@([^\s]*)$/)
  if (match === null || match[1] === undefined) return undefined
  return match[1]
}

export const mentionPopoverOpen = (draft: string, dismissed: boolean): boolean =>
  !dismissed && mentionQueryFromDraft(draft) !== undefined

export const mentionChoices = (
  draft: string,
  mentionQuery: string,
  files: ReadonlyArray<{ readonly path: string; readonly absPath: string }>,
  dismissed: boolean,
): ReadonlyArray<{ readonly path: string; readonly absPath: string }> => {
  if (!mentionPopoverOpen(draft, dismissed)) return []
  const query = mentionQueryFromDraft(draft)
  if (query === undefined || query !== mentionQuery) return []
  return files
}

export const moveMentionIndex = (
  current: number | undefined,
  key: "ArrowUp" | "ArrowDown",
  count: number,
): number | undefined => {
  if (count <= 0) return undefined
  if (current === undefined) return 0
  if (key === "ArrowDown") return Math.min(count - 1, current + 1)
  return Math.max(0, current - 1)
}
