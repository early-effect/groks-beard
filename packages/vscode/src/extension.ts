import { chipFromFile, chipFromSelection, formatAtRef } from "@groks-beard/core"
import { Layer, ManagedRuntime } from "effect"
import { existsSync } from "node:fs"
import * as vscode from "vscode"
import { ChangesTreeProvider } from "./changes-tree.js"
import { ChatViewProvider } from "./chat-view.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import { missingCliMessage } from "./onboarding.js"

let runtime: ManagedRuntime.ManagedRuntime<never, never> | undefined
const composer = new ComposerState()
let chat: ChatViewProvider | undefined

const workspaceRoot = (): string | undefined => vscode.workspace.workspaceFolders?.[0]?.uri.fsPath

const activeChip = () => {
  const editor = vscode.window.activeTextEditor
  if (editor === undefined) return undefined
  const sel = editor.selection
  const absPath = editor.document.uri.fsPath
  const root = workspaceRoot()
  const languageId = editor.document.languageId
  if (sel.isEmpty) {
    return chipFromFile({
      absPath,
      source: "active",
      ...(root !== undefined ? { workspaceRoot: root } : {}),
      ...(languageId !== "" ? { languageId } : {}),
    })
  }
  return chipFromSelection({
    absPath,
    startLine: sel.start.line + 1,
    endLine: sel.end.line + 1,
    ...(root !== undefined ? { workspaceRoot: root } : {}),
    ...(languageId !== "" ? { languageId } : {}),
  })
}

export const activate = (context: vscode.ExtensionContext): void => {
  runtime = ManagedRuntime.make(Layer.empty)
  chat = new ChatViewProvider(context, composer, runtime)
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatViewProvider.viewId, chat, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.window.registerTreeDataProvider(ChangesTreeProvider.viewId, new ChangesTreeProvider()),
    vscode.commands.registerCommand("groksBeard.open", () => {
      void vscode.commands.executeCommand(`${ChatViewProvider.viewId}.focus`)
      const rt = runtime
      const view = chat
      if (rt === undefined || view === undefined) return
      const cliPath = vscode.workspace.getConfiguration("groksBeard").get<string>("cliPath") ?? ""
      void rt.runPromise(locateGrokCli({
        ...(cliPath !== "" ? { cliPath } : {}),
        env: process.env as Record<string, string | undefined>,
        exists: existsSync,
        win: process.platform === "win32",
      })).then(
        () => {
          void view.ensureAgent()
        },
        (error: unknown) => {
          const searched = error instanceof Error && "searched" in error
            ? (error as { searched: ReadonlyArray<string> }).searched
            : ["PATH"]
          void vscode.window.showErrorMessage(missingCliMessage(searched))
        },
      )
    }),
    vscode.commands.registerCommand("groksBeard.cancel", () => {
      void chat?.cancel()
    }),
    vscode.commands.registerCommand("groksBeard.cycleMode", () => {
      void chat?.cycleMode()
    }),
    vscode.commands.registerCommand("groksBeard.addSelection", () => {
      const chip = activeChip()
      if (chip === undefined) {
        void vscode.window.showWarningMessage("No active editor.")
        return
      }
      composer.addChip(chip)
      composer.setPendingSelection(chip)
      void vscode.window.showInformationMessage(`Added ${formatAtRef(chip)}`)
    }),
    vscode.commands.registerCommand("groksBeard.addFile", () => {
      const editor = vscode.window.activeTextEditor
      if (editor === undefined) {
        void vscode.window.showWarningMessage("No active editor.")
        return
      }
      const root = workspaceRoot()
      const languageId = editor.document.languageId
      const chip = chipFromFile({
        absPath: editor.document.uri.fsPath,
        source: "file",
        ...(root !== undefined ? { workspaceRoot: root } : {}),
        ...(languageId !== "" ? { languageId } : {}),
      })
      composer.addChip(chip)
      void vscode.window.showInformationMessage(`Added ${formatAtRef(chip)}`)
    }),
    vscode.commands.registerCommand("groksBeard.copySelectionAsGrokRef", async () => {
      const chip = activeChip()
      if (chip === undefined) {
        void vscode.window.showWarningMessage("No active editor.")
        return
      }
      composer.setPendingSelection(chip)
      await vscode.env.clipboard.writeText(formatAtRef(chip))
    }),
  )
}

export const deactivate = (): Thenable<void> | undefined => {
  chat?.dispose()
  chat = undefined
  const rt = runtime
  runtime = undefined
  return rt?.dispose()
}
