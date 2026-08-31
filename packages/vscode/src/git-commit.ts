export const defaultCommitMessage = (titles: ReadonlyArray<string>): string => {
  const unique: Array<string> = []
  for (const title of titles) {
    const trimmed = title.trim()
    if (trimmed === "" || trimmed === "Untitled") continue
    if (!unique.includes(trimmed)) unique.push(trimmed)
  }
  if (unique.length === 0) return "Grok changes"
  if (unique.length === 1) return unique[0] ?? "Grok changes"
  return unique.slice(0, 3).join("; ")
}

export type GitCommitPorts = {
  readonly add: (paths: ReadonlyArray<string>) => Promise<void>
  readonly commit: (message: string) => Promise<void>
}

export const commitPaths = async (
  paths: ReadonlyArray<string>,
  message: string,
  ports: GitCommitPorts,
): Promise<number> => {
  const unique = [...new Set(paths.filter((path) => path !== ""))]
  if (unique.length === 0) throw new Error("No files to commit")
  const trimmed = message.trim()
  if (trimmed === "") throw new Error("Commit message is empty")
  await ports.add(unique)
  await ports.commit(trimmed)
  return unique.length
}

export const gitPortsForPaths = async (
  paths: ReadonlyArray<string>,
  resolveGit: () => Promise<
    | {
      readonly repositories: Array<{
        readonly add: (paths: Array<string>) => Promise<void>
        readonly commit: (message: string) => Promise<void>
      }>
      readonly getRepository: (
        uri: { readonly fsPath: string },
      ) => {
        readonly add: (paths: Array<string>) => Promise<void>
        readonly commit: (message: string) => Promise<void>
      } | null
    }
    | undefined
  >,
  fileUri: (path: string) => { readonly fsPath: string },
): Promise<GitCommitPorts> => {
  const api = await resolveGit()
  if (api === undefined) throw new Error("Git extension is not available")
  const first = paths.find((path) => path !== "")
  const repo = first !== undefined
    ? api.getRepository(fileUri(first)) ?? api.repositories[0]
    : api.repositories[0]
  if (repo === undefined) throw new Error("No Git repository found")
  return {
    add: (rows) => repo.add([...rows]),
    commit: (message) => repo.commit(message),
  }
}
