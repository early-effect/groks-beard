import { chipFromFile, chipFromSelection, formatAtRef, NodeNotFound } from "@groks-beard/core"
import { Effect, Layer, ManagedRuntime } from "effect"
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import { dirname } from "node:path"
import * as vscode from "vscode"
import { CHANGES_PANE_CONTEXT, changesPresentationFrom } from "./changes-presentation.js"
import { ChangesReviewPanel } from "./changes-review.js"
import {
  type ChangesNode,
  ChangesTreeProvider,
  findPendingFile,
  parseChangesNode,
} from "./changes-tree.js"
import { ChatViewProvider } from "./chat-view.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import { commitGrokFiles } from "./git-commit-host.js"
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
import {
  createReviewHost,
  registerVirtualDocs,
  reportUndoResult,
  vscodeUndoPorts,
} from "./review-vscode.js"
import { TUI_BRIDGE_STATE_KEY, TuiBridge } from "./tui-bridge.js"
import {
  CHANGES_VIEW_ID,
  CHANGES_VIEW_ID_SECONDARY,
  changesViewIdForHost,
  CHAT_VIEW_ID,
  CHAT_VIEW_ID_SECONDARY,
  chatViewIdForHost,
  isVsCodeHost,
  USE_ACTIVITY_BAR_CONTEXT,
} from "./view-placement.js"
import { filePathFromEditorUri } from "./virtual-docs.js"

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
  const excerpt = editor.document.getText(sel)
  return chipFromSelection({
    absPath,
    startLine: sel.start.line + 1,
    endLine: sel.end.line + 1,
    ...(root !== undefined ? { workspaceRoot: root } : {}),
    ...(languageId !== "" ? { languageId } : {}),
    ...(excerpt !== "" ? { excerpt } : {}),
  })
}

const asNode = (arg: unknown): ChangesNode | undefined => {
  if (typeof arg === "string") return parseChangesNode(arg)
  if (typeof arg === "object" && arg !== null && "type" in arg) return arg as ChangesNode
  return undefined
}

const nodeFromArg = (
  arg: unknown,
  store: ReviewHost["store"],
): ChangesNode | undefined => {
  const node = asNode(arg)
  if (node !== undefined) return node
  const uri = arg instanceof vscode.Uri ? arg : vscode.window.activeTextEditor?.document.uri
  if (uri === undefined) return undefined
  return findPendingFile(store, filePathFromEditorUri(uri))
}

const reportCommit = async (
  paths: ReadonlyArray<string>,
  titles: ReadonlyArray<string>,
  onOk: () => void,
): Promise<void> => {
  try {
    const count = await commitGrokFiles(paths, titles)
    if (count === undefined) return
    onOk()
    void vscode.window.showInformationMessage(
      count === 1 ? "Committed 1 file." : `Committed ${count} files.`,
    )
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    void vscode.window.showErrorMessage(`Commit failed: ${message}`)
  }
}

const registerChangeCommands = (
  context: vscode.ExtensionContext,
  review: ReviewHost,
  status: vscode.StatusBarItem,
): void => {
  const refreshStatus = () => {
    const summary = review.store.pendingSummary()
    const files = summary.fileCount === 1 ? "1 file" : `${summary.fileCount} files`
    status.text = `$(diff) ${files} ${review.store.statusText()}`
    status.tooltip = review.store.undoReason() ?? "Grok Changes"
    if (summary.fileCount > 0) status.show()
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
      const node = nodeFromArg(arg, review.store)
      if (node?.type === "file") {
        void review.openFileDiff(node.sessionId, node.turnId, node.path)
      }
    }),
    vscode.commands.registerCommand("groksBeard.keepChange", (arg?: unknown) => {
      const node = nodeFromArg(arg, review.store)
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
      const node = nodeFromArg(arg, review.store)
      if (node?.type !== "file") return
      reportUndoResult(await review.undo(node.sessionId, node.turnId, node.path, ports))
    }),
    vscode.commands.registerCommand("groksBeard.undoAll", async (arg?: unknown) => {
      const node = asNode(arg)
      if (node?.type === "turn") {
        reportUndoResult(await review.undoAll(node.sessionId, node.turnId, ports))
        return
      }
      reportUndoResult(await review.store.undoEvery(ports))
    }),
  )
}

export const activate = (context: vscode.ExtensionContext): void => {
  runtime = ManagedRuntime.make(Layer.empty)
  const { review, provider } = createReviewHost(context)
  registerVirtualDocs(context, provider)
  chat = new ChatViewProvider(context, composer, runtime, review)
  const tree = new ChangesTreeProvider(review.store)
  const reviewPanel = new ChangesReviewPanel(context, review, workspaceRoot)
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10)
  const syncPresentation = () => {
    const presentation = changesPresentationFrom(
      vscode.workspace.getConfiguration("groksBeard").get("changesPresentation"),
    )
    const pane = presentation === "pane"
    void vscode.commands.executeCommand("setContext", CHANGES_PANE_CONTEXT, pane)
    status.command = pane
      ? `${changesViewIdForHost(vscode.env.appName)}.focus`
      : "groksBeard.openChangesReview"
    chat?.syncChangesToast()
  }
  registerChangeCommands(context, review, status)
  syncPresentation()
  if (!isVsCodeHost(vscode.env.appName)) {
    void vscode.commands.executeCommand("setContext", USE_ACTIVITY_BAR_CONTEXT, true)
  }
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
    vscode.window.registerWebviewViewProvider(CHAT_VIEW_ID, chat, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.window.registerWebviewViewProvider(CHAT_VIEW_ID_SECONDARY, chat, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.window.registerTreeDataProvider(CHANGES_VIEW_ID, tree),
    vscode.window.registerTreeDataProvider(CHANGES_VIEW_ID_SECONDARY, tree),
    vscode.commands.registerCommand("groksBeard.open", () => {
      void vscode.commands.executeCommand(`${chatViewIdForHost(vscode.env.appName)}.focus`)
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
    vscode.commands.registerCommand("groksBeard.openChangesReview", () => {
      reviewPanel.reveal()
    }),
    vscode.commands.registerCommand("groksBeard.commitChanges", () => {
      const sets = review.store.list()
      void reportCommit(
        sets.flatMap((set) => set.files.map((file) => file.path)),
        sets.map((set) => set.title),
        () => review.store.keepEvery(),
      )
    }),
    vscode.commands.registerCommand("groksBeard.commitChange", (arg?: unknown) => {
      const node = nodeFromArg(arg, review.store)
      if (node?.type !== "file") {
        void vscode.commands.executeCommand("groksBeard.commitChanges")
        return
      }
      const set = review.store.getTurn(node.sessionId, node.turnId)
      void reportCommit([node.path], [set?.title ?? "Grok changes"], () => {
        review.keep(node.sessionId, node.turnId, node.path)
      })
    }),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration("groksBeard.changesPresentation")) syncPresentation()
    }),
    vscode.commands.registerCommand("groksBeard.openSettings", () => {
      void vscode.commands.executeCommand("workbench.action.openSettings", "groksBeard")
    }),
    vscode.commands.registerCommand("groksBeard.addSelection", (raw?: unknown) => {
      chat?.addSelectionToChat(raw)
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
