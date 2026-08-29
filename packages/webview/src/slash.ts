export type SlashCommand = {
  readonly name: string
  readonly description: string
  readonly hint?: string
}

export const slashQueryFromDraft = (draft: string): string | undefined => {
  if (!draft.startsWith("/")) return undefined
  const first = draft.split(/\s/, 1)[0] ?? draft
  if (first.includes("\n")) return undefined
  return first.slice(1)
}

export const filterSlashCommands = (
  commands: ReadonlyArray<SlashCommand>,
  query: string,
): ReadonlyArray<SlashCommand> => {
  const q = query.replace(/^\//, "").toLowerCase()
  if (q === "") return commands
  const prefix: Array<SlashCommand> = []
  const mid: Array<SlashCommand> = []
  const desc: Array<SlashCommand> = []
  for (const command of commands) {
    const name = command.name.toLowerCase()
    if (name.startsWith(q)) prefix.push(command)
    else if (name.includes(q)) mid.push(command)
    else if (command.description.toLowerCase().includes(q)) desc.push(command)
  }
  return [...prefix, ...mid, ...desc]
}
