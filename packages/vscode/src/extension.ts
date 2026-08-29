import { chipFromFile, chipFromSelection, formatAtRef, NodeNotFound } from "@groks-beard/core"
import { Effect, Layer, ManagedRuntime } from "effect"
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import { dirname } from "node:path"
import * as vscode from "vscode"
import type { UndoApplyResult } from "./change-store.js"
import { type ChangesNode, ChangesTreeProvider, parseChangesNode } from "./changes-tree.js"
import { ChatViewProvider } from "./chat-view.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import { vscodeMcpHandle } from "./mcp-host-vscode.js"
import {
  mergeMcpTable,
  projectGrokConfigPath,
  removeMcpTable,
  renderMcpTable,
  TUI_BRIDGE_REFRESH_MESSAGE,
} from "./mcp-toml.js"
import { locateNode } from "./node-locator.js"
import { missingCliMessage, missingNodeMessage } from "./onboarding.js"
import type { ReviewHost } from "./review-host.js"
import { createReviewHost, registerVirtualDocs, vscodeUndoPorts } from "./review-vscode.js"
import { TUI_BRIDGE_STATE_KEY, TuiBridge } from "./tui-bridge.js"
import { maybePlaceViews, VIEW_PLACEMENT_KEY, type ViewPlacement } from "./view-placement.js"

let runtime: ManagedRuntime.ManagedRuntime<never, never> | undefined
const composer = new ComposerState()
let chat: ChatViewProvider | undefined
let tuiBridge: TuiBridge | undefined

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
      void vscode.window.showWarningMessage(
        "Open diff from a permission card or a Grok Changes file.",
      )
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
  const output = vscode.window.createOutputChannel("Grok's Beard")
  const handle = vscodeMcpHandle(
    composer,
    review,
    review.store,
    workspaceRoot,
    (message) => {
      void vscode.window.showInformationMessage(message)
    },
  )
  tuiBridge = new TuiBridge({
    getEnabled: () => context.workspaceState.get(TUI_BRIDGE_STATE_KEY) === true,
    workspace: workspaceRoot,
    handle: (request) => handle(request),
    log: (message) => output.appendLine(message),
  })
  void tuiBridge.sync()
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
    output,
    { dispose: () => void tuiBridge?.unbind() },
    vscode.commands.registerCommand("groksBeard.enableTuiBridge", () => {
      void enableTuiBridge(context)
    }),
    vscode.commands.registerCommand("groksBeard.disableTuiBridge", () => {
      void disableTuiBridge(context)
    }),
  )
}

const mcpProxyPath = (context: vscode.ExtensionContext): string =>
  vscode.Uri.joinPath(context.extensionUri, "dist", "mcp-proxy.js").fsPath

const enableTuiBridge = async (context: vscode.ExtensionContext): Promise<void> => {
  const workspace = workspaceRoot()
  if (workspace === undefined) {
    void vscode.window.showErrorMessage("Open a workspace folder to enable the TUI bridge.")
    return
  }
  const nodePath = vscode.workspace.getConfiguration("groksBeard").get<string>("nodePath") ?? ""
  let nodeCommand = "node"
  let nodeError: NodeNotFound | undefined
  try {
    nodeCommand = await Effect.runPromise(locateNode({
      ...(nodePath !== "" ? { nodePath } : {}),
      env: process.env as Record<string, string | undefined>,
      exists: existsSync,
      win: process.platform === "win32",
    }))
  } catch (cause) {
    if (cause instanceof NodeNotFound) nodeError = cause
    else throw cause
  }
  const proxyPath = mcpProxyPath(context)
  const snippet = renderMcpTable(nodeCommand, proxyPath, workspace)
  const write = "Write project .grok/config.toml"
  const copy = "Copy snippet"
  if (nodeError !== undefined) {
    const pick = await vscode.window.showWarningMessage(
      missingNodeMessage(nodeError.searched),
      copy,
    )
    if (pick === copy) await vscode.env.clipboard.writeText(snippet)
  } else {
    const pick = await vscode.window.showInformationMessage(
      "Enable TUI Bridge for this workspace?",
      write,
      copy,
    )
    if (pick === write) {
      const configPath = projectGrokConfigPath(workspace)
      mkdirSync(dirname(configPath), { recursive: true })
      let existing = ""
      try {
        existing = readFileSync(configPath, "utf8")
      } catch {
        existing = ""
      }
      writeFileSync(configPath, mergeMcpTable(existing, snippet), "utf8")
      void vscode.window.showInformationMessage(TUI_BRIDGE_REFRESH_MESSAGE)
    } else if (pick === copy) {
      await vscode.env.clipboard.writeText(snippet)
    }
  }
  await context.workspaceState.update(TUI_BRIDGE_STATE_KEY, true)
  await tuiBridge?.sync()
}

const disableTuiBridge = async (context: vscode.ExtensionContext): Promise<void> => {
  await context.workspaceState.update(TUI_BRIDGE_STATE_KEY, false)
  await tuiBridge?.unbind()
  const workspace = workspaceRoot()
  const remove = "Remove from project .grok/config.toml"
  const pick = await vscode.window.showInformationMessage("TUI bridge disabled.", remove)
  if (pick !== remove || workspace === undefined) return
  const configPath = projectGrokConfigPath(workspace)
  try {
    const existing = readFileSync(configPath, "utf8")
    writeFileSync(configPath, removeMcpTable(existing), "utf8")
  } catch {
    return
  }
}

export const deactivate = (): Thenable<void> | undefined => {
  chat?.dispose()
  chat = undefined
  const bridge = tuiBridge
  tuiBridge = undefined
  const rt = runtime
  runtime = undefined
  return Promise.all([bridge?.unbind(), rt?.dispose()]).then(() => undefined)
}
