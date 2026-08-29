import { chipFromFile, chipFromSelection, formatAtRef } from "@groks-beard/core"
import { Layer, ManagedRuntime } from "effect"
import { existsSync } from "node:fs"
import * as vscode from "vscode"
import type { UndoApplyResult } from "./change-store.js"
import { ChangesTreeProvider, type ChangesNode, parseChangesNode } from "./changes-tree.js"
import { ChatViewProvider } from "./chat-view.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import { missingCliMessage } from "./onboarding.js"
import type { ReviewHost } from "./review-host.js"
import { createReviewHost, registerVirtualDocs, vscodeUndoPorts } from "./review-vscode.js"
import { maybePlaceViews, VIEW_PLACEMENT_KEY, type ViewPlacement } from "./view-placement.js"

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

const asNode = (arg: unknown): ChangesNode | undefined => {
  if (typeof arg === "string") return parseChangesNode(arg)
  if (typeof arg === "object" && arg !== null && "type" in arg) return arg as ChangesNode
  return undefined
}

const reportUndo = (result: UndoApplyResult): void => {
  if (result.ok) return
  if (result.cancelled === true) {
    void vscode.window.showInformationMessage(`Undo cancelled for ${result.path}.`)
    return
  }
  void vscode.window.showWarningMessage(`Undo stopped on ${result.path}: ${result.reason}`)
}

const registerChangeCommands = (
  context: vscode.ExtensionContext,
  review: ReviewHost,
  status: vscode.StatusBarItem,
): void => {
  const refreshStatus = () => {
    const pending = review.store.list().reduce((n, set) => n + set.files.length, 0)
    status.text = `$(diff) ${review.store.statusText()}`
    status.tooltip = review.store.undoReason() ?? "Grok Changes"
    if (pending > 0) status.show()
    else status.hide()
  }
  review.store.onChange(refreshStatus)
  refreshStatus()

  const ports = vscodeUndoPorts()
  context.subscriptions.push(
    status,
    vscode.commands.registerCommand("groksBeard.openDiff", (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "file") {
        void review.openFileDiff(node.sessionId, node.turnId, node.path)
        return
      }
      void vscode.window.showWarningMessage("Open diff from a permission card or a Grok Changes file.")
    }),
    vscode.commands.registerCommand("groksBeard.openChangeDiff", (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "file") {
        void review.openFileDiff(node.sessionId, node.turnId, node.path)
      }
    }),
    vscode.commands.registerCommand("groksBeard.keepChange", (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "file") review.keep(node.sessionId, node.turnId, node.path)
    }),
    vscode.commands.registerCommand("groksBeard.keepAll", (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "turn") {
        review.keepAll(node.sessionId, node.turnId)
        return
      }
      review.store.keepEvery()
    }),
    vscode.commands.registerCommand("groksBeard.undoChange", async (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type !== "file") return
      reportUndo(await review.undo(node.sessionId, node.turnId, node.path, ports))
    }),
    vscode.commands.registerCommand("groksBeard.undoAll", async (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "turn") {
        reportUndo(await review.undoAll(node.sessionId, node.turnId, ports))
        return
      }
      reportUndo(await review.store.undoEvery(ports))
    }),
  )
}

export const activate = (context: vscode.ExtensionContext): void => {
  runtime = ManagedRuntime.make(Layer.empty)
  const { review, provider } = createReviewHost(context)
  registerVirtualDocs(context, provider)
  chat = new ChatViewProvider(context, composer, runtime, review)
  const tree = new ChangesTreeProvider(review.store)
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10)
  status.command = "groksBeard.changes.focus"
  registerChangeCommands(context, review, status)
  const persisted = context.workspaceState.get<ViewPlacement>(VIEW_PLACEMENT_KEY)
  void maybePlaceViews({
    appName: vscode.env.appName,
    persisted,
    persist: (placement) => context.workspaceState.update(VIEW_PLACEMENT_KEY, placement),
    moveViews: (viewIds, destinationId) =>
      vscode.commands.executeCommand("vscode.moveViews", { viewIds, destinationId }),
  })
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatViewProvider.viewId, chat, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.window.registerTreeDataProvider(ChangesTreeProvider.viewId, tree),
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
