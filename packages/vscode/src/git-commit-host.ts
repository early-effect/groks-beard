import * as vscode from "vscode"
import { commitPaths, defaultCommitMessage, gitPortsForPaths } from "./git-commit.js"

export const promptCommitMessage = async (seed: string): Promise<string | undefined> => {
  const value = await vscode.window.showInputBox({
    title: "Commit Grok changes",
    prompt: "Commit message",
    value: seed,
    ignoreFocusOut: true,
  })
  if (value === undefined) return undefined
  const trimmed = value.trim()
  return trimmed === "" ? undefined : trimmed
}

export const commitGrokFiles = async (
  paths: ReadonlyArray<string>,
  titles: ReadonlyArray<string>,
): Promise<number | undefined> => {
  const message = await promptCommitMessage(defaultCommitMessage(titles))
  if (message === undefined) return undefined
  const ext = vscode.extensions.getExtension("vscode.git")
  const ports = await gitPortsForPaths(
    paths,
    async () => {
      if (ext === undefined) return undefined
      const activated = await ext.activate() as {
        getAPI?: (version: number) => {
          repositories: Array<{
            add: (paths: Array<string>) => Promise<void>
            commit: (message: string) => Promise<void>
          }>
          getRepository: (uri: { fsPath: string }) => {
            add: (paths: Array<string>) => Promise<void>
            commit: (message: string) => Promise<void>
          } | null
        }
      }
      return activated.getAPI?.(1)
    },
    (path) => vscode.Uri.file(path),
  )
  return commitPaths(paths, message, ports)
}
