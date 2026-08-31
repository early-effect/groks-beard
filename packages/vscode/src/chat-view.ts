import {
  fakeSpawnCapabilityPolicy,
  GROK_AGENT_STDIO_ARGS,
  grokAgentStdioArgs,
  initializeAgent,
  killSpawnedAgent,
  listSessionMcp,
  liveSpawnCapabilityPolicy,
  readGrokVersionBanner,
  resolveGrokVersion,
  type SpawnedAgent,
  spawnGrokAgentStdio,
  toggleSessionMcpTool,
} from "@groks-beard/acp"
import {
  chipFromFile,
  chipFromSelection,
  decodeWebviewMsg,
  grokHome,
  type HostMsg,
  type PromptChip,
  workspaceRelativePath,
} from "@groks-beard/core"
import { Effect, type ManagedRuntime } from "effect"
import { existsSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import * as vscode from "vscode"
import {
  parsePreviewSelection,
  previewResourcePath,
  selectionToAdd,
  type VisibleEditorSelection,
} from "./add-selection.js"
import { changesPresentationFrom } from "./changes-presentation.js"
import { chatHtml } from "./chat-html.js"
import { ChatRuntime } from "./chat-runtime.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import {
  MENTION_FILE_LIMIT,
  mentionFileExclude,
  mentionFilePattern,
  rankMentionFiles,
} from "./file-search.js"
import { grantFolderTrust, loadMcpCatalog, setMcpServerEnabled } from "./grok-mcp-run.js"
import {
  folderTrustDismissKey,
  folderTrustPromptMessage,
  type McpDoctorReport,
  mergeDoctorServer,
  overlayMcpTools,
  parseMcpListServers,
  reportNeedsFolderTrust,
  untrustedServerNames,
} from "./grok-mcp.js"
import { readGrokModelCatalog } from "./grok-models.js"
import { dispatchWebviewMsg, type WebviewHandlers } from "./host-dispatch.js"
import { missingCliMessage } from "./onboarding.js"
import { resolveChatEditorFile, resolvePlanFile } from "./plan-preview.js"
import type { ReviewHost } from "./review-host.js"
import { reportUndoResult, vscodeUndoPorts } from "./review-vscode.js"
import { createHostTerminalManager } from "./terminal-manager.js"
import { changesViewIdForHost, chatViewIdForHost } from "./view-placement.js"

const lineRange = (
  doc: vscode.TextDocument,
  startLine: number,
  endLine: number,
): vscode.Range => {
  const start = Math.max(0, startLine - 1)
  const end = Math.max(start, Math.min(doc.lineCount - 1, endLine - 1))
  return new vscode.Range(start, 0, end, doc.lineAt(end).text.length)
}

export class ChatViewProvider implements vscode.WebviewViewProvider {
  static readonly viewId = "groksBeard.chat"

  private view: vscode.WebviewView | undefined
  private runtime: ChatRuntime | undefined
  private spawned: SpawnedAgent | undefined
  private starting: Promise<void> | undefined
  private readonly pending: Array<HostMsg> = []
  private readonly unsubStore: () => void
  private readonly unsubConfig: vscode.Disposable
  private mcpEpoch = 0
  private lastMcpReport: McpDoctorReport | undefined
  private lastPlanMarkdown = ""
  private planPreviewPath: string | undefined
  private mcpToolsTimer: ReturnType<typeof setTimeout> | undefined
  private editorTimer: ReturnType<typeof setTimeout> | undefined
  private lastEditorKey = ""
  private readonly unsubEditor: vscode.Disposable
  private readonly unsubSelection: vscode.Disposable
  private readonly unsubTabs: vscode.Disposable
  private readonly unsubTabGroups: vscode.Disposable
  private grokTrustGranted = false
  private trustPromptShown = false

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly composer: ComposerState,
    private readonly effectRuntime: ManagedRuntime.ManagedRuntime<never, never>,
    private readonly review: ReviewHost,
  ) {
    this.unsubStore = review.store.onChange(() => this.syncChangesToast())
    this.unsubConfig = vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration("groksBeard")) {
        this.postSettings()
        this.syncChangesToast()
      }
    })
    this.unsubEditor = vscode.window.onDidChangeActiveTextEditor(() => {
      this.scheduleEditorContext()
    })
    this.unsubSelection = vscode.window.onDidChangeTextEditorSelection(() => {
      this.scheduleEditorContext()
    })
    this.unsubTabs = vscode.window.tabGroups.onDidChangeTabs(() => {
      this.scheduleEditorContext()
    })
    this.unsubTabGroups = vscode.window.tabGroups.onDidChangeTabGroups(() => {
      this.scheduleEditorContext()
    })
  }

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView
    const webview = webviewView.webview
    webview.options = {
      enableScripts: true,
      localResourceRoots: [this.context.extensionUri],
    }
    const scriptUri = webview.asWebviewUri(
      vscode.Uri.joinPath(this.context.extensionUri, "dist", "webview", "chat.js"),
    )
    const logoUri = webview.asWebviewUri(
      vscode.Uri.joinPath(this.context.extensionUri, "media", "logo.png"),
    )
    const ctrlEnter = vscode.workspace.getConfiguration("groksBeard").get<boolean>(
      "useCtrlEnterToSend",
    ) ?? false
    webview.html = chatHtml({
      cspSource: webview.cspSource,
      scriptUri: scriptUri.toString(),
      logoUri: logoUri.toString(),
      ctrlEnterToSend: ctrlEnter,
    })
    webview.onDidReceiveMessage((raw: unknown) => {
      try {
        const msg = decodeWebviewMsg(raw)
        dispatchWebviewMsg(msg, this.handlers())
      } catch (cause) {
        const message = cause instanceof Error ? cause.message : String(cause)
        this.post({ _tag: "error", message })
      }
    })
    for (const msg of this.pending.splice(0)) this.post(msg)
    this.syncChangesToast()
    this.postSettings()
    this.postEditorContext()
    void this.ensureAgent()
  }

  dispose(): void {
    this.unsubConfig.dispose()
    this.unsubEditor.dispose()
    this.unsubSelection.dispose()
    this.unsubTabs.dispose()
    this.unsubTabGroups.dispose()
    this.unsubStore()
    if (this.mcpToolsTimer !== undefined) clearTimeout(this.mcpToolsTimer)
    if (this.editorTimer !== undefined) clearTimeout(this.editorTimer)
    void vscode.commands.executeCommand("setContext", "groksBeard.turnRunning", false)
    if (this.spawned !== undefined) killSpawnedAgent(this.spawned)
    this.spawned = undefined
    this.runtime = undefined
  }

  syncChangesToast(): void {
    const presentation = changesPresentationFrom(
      vscode.workspace.getConfiguration("groksBeard").get("changesPresentation"),
    )
    if (presentation !== "toast") {
      this.post({ _tag: "changesSummary", fileCount: 0, additions: 0, deletions: 0 })
      return
    }
    const summary = this.review.store.pendingSummary()
    this.post({
      _tag: "changesSummary",
      fileCount: summary.fileCount,
      additions: summary.additions,
      deletions: summary.deletions,
    })
  }

  async ensureAgent(): Promise<void> {
    if (this.runtime !== undefined) return
    if (this.starting !== undefined) return this.starting
    this.starting = this.startAgent().finally(() => {
      this.starting = undefined
    })
    return this.starting
  }

  async cancel(): Promise<void> {
    await this.runtime?.cancel()
  }

  async cycleMode(): Promise<void> {
    await this.ensureAgent()
    await this.runtime?.cycleMode()
  }

  async setMode(modeId: string): Promise<void> {
    await this.ensureAgent()
    await this.runtime?.setMode(modeId)
  }

  async setModel(modelId: string): Promise<void> {
    await this.ensureAgent()
    await this.runtime?.setModel(modelId)
  }

  addSelectionToChat(raw?: unknown): void {
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab
    const editors = this.visibleEditorSelections()
    const previewPath = previewResourcePath({
      ...(tab !== undefined ? { tabInput: tab.input, tabLabel: tab.label } : {}),
      editors,
    })
    const payload = parsePreviewSelection(raw)
    const active = vscode.window.activeTextEditor
    const picked = selectionToAdd({
      editors,
      ...(payload !== undefined ? { payload } : {}),
      ...(previewPath !== undefined ? { previewPath } : {}),
      ...(active !== undefined
        ? {
          activeEditor: {
            fsPath: active.document.uri.fsPath,
            empty: active.selection.isEmpty,
            startLine: active.selection.start.line + 1,
            startCol: active.selection.start.character + 1,
            endLine: active.selection.end.line + 1,
            endCol: active.selection.end.character + 1,
            ...(active.selection.isEmpty
              ? {}
              : { excerpt: active.document.getText(active.selection) }),
            ...(active.document.languageId !== ""
              ? { languageId: active.document.languageId }
              : {}),
          },
        }
        : {}),
    })
    if (picked === undefined) {
      void vscode.window.showWarningMessage("No selection to add to chat.")
      return
    }
    const root = this.workspaceRoot()
    const chip = chipFromSelection({
      absPath: picked.absPath,
      excerpt: picked.excerpt,
      ...(root !== undefined ? { workspaceRoot: root } : {}),
      ...(picked.startLine !== undefined ? { startLine: picked.startLine } : {}),
      ...(picked.endLine !== undefined ? { endLine: picked.endLine } : {}),
      ...(picked.languageId !== undefined ? { languageId: picked.languageId } : {}),
    })
    this.composer.setPendingSelection(chip)
    this.post({
      _tag: "composerChip",
      path: chip.path,
      absPath: chip.absPath,
      source: chip.source,
      ...(chip.startLine !== undefined ? { startLine: chip.startLine } : {}),
      ...(chip.endLine !== undefined ? { endLine: chip.endLine } : {}),
      ...(chip.languageId !== undefined ? { languageId: chip.languageId } : {}),
      ...(chip.excerpt !== undefined ? { excerpt: chip.excerpt } : {}),
    })
    void vscode.commands.executeCommand(`${chatViewIdForHost(vscode.env.appName)}.focus`)
  }

  private post(msg: HostMsg): void {
    if (msg._tag === "planCard" && msg.planMarkdown !== "") {
      this.lastPlanMarkdown = msg.planMarkdown
    }
    if (msg._tag === "userMessage") {
      void vscode.commands.executeCommand("setContext", "groksBeard.turnRunning", true)
    }
    if (msg._tag === "turnEnd" || msg._tag === "clearTranscript") {
      void vscode.commands.executeCommand("setContext", "groksBeard.turnRunning", false)
    }
    if (this.view === undefined) {
      this.pending.push(msg)
      return
    }
    void this.view.webview.postMessage(msg)
  }

  private workspaceRoot(): string | undefined {
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath
  }

  private trustGrokFolder(): boolean {
    return this.grokTrustGranted
  }

  private scheduleEditorContext(): void {
    if (this.editorTimer !== undefined) clearTimeout(this.editorTimer)
    this.editorTimer = setTimeout(() => {
      this.editorTimer = undefined
      this.postEditorContext()
    }, 80)
  }

  private currentChatFile():
    | { readonly absPath: string; readonly fromPlanPreview: boolean }
    | undefined
  {
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab
    const editor = vscode.window.activeTextEditor
    return resolveChatEditorFile({
      ...(tab !== undefined ? { activeTab: { label: tab.label, input: tab.input } } : {}),
      ...(editor !== undefined
        ? { editor: { fsPath: editor.document.uri.fsPath, scheme: editor.document.uri.scheme } }
        : {}),
      ...(this.planPreviewPath !== undefined ? { knownPlanPath: this.planPreviewPath } : {}),
    })
  }

  private visibleEditorSelections(): ReadonlyArray<VisibleEditorSelection> {
    return vscode.window.visibleTextEditors.flatMap((editor) => {
      const scheme = editor.document.uri.scheme
      if (scheme !== "file" && scheme !== "untitled") return []
      const sel = editor.selection
      const excerpt = sel.isEmpty ? undefined : editor.document.getText(sel)
      return [{
        fsPath: editor.document.uri.fsPath,
        empty: sel.isEmpty,
        startLine: sel.start.line + 1,
        startCol: sel.start.character + 1,
        endLine: sel.end.line + 1,
        endCol: sel.end.character + 1,
        ...(excerpt !== undefined && excerpt !== "" ? { excerpt } : {}),
        ...(editor.document.languageId !== "" ? { languageId: editor.document.languageId } : {}),
      }]
    })
  }

  private matchingEditor(absPath: string): VisibleEditorSelection | undefined {
    return this.visibleEditorSelections().find((editor) => editor.fsPath === absPath)
  }

  private postEditorContext(): void {
    const current = this.currentChatFile()
    if (current === undefined) {
      if (this.lastEditorKey === "") return
      this.lastEditorKey = ""
      this.post({ _tag: "editorContext", hasSelection: false })
      return
    }
    const path = workspaceRelativePath(current.absPath, this.workspaceRoot())
    const match = this.matchingEditor(current.absPath)
    const hasSelection = match !== undefined && !match.empty
    const startLine = match?.startLine ?? 1
    const startCol = match?.startCol ?? 1
    const endLine = match?.endLine ?? startLine
    const endCol = match?.endCol ?? 1
    const key = `${path}:${startLine}:${startCol}:${endLine}:${endCol}:${hasSelection}`
    if (key === this.lastEditorKey) return
    this.lastEditorKey = key
    this.post({
      _tag: "editorContext",
      path,
      startLine,
      startCol,
      endLine,
      endCol,
      hasSelection,
    })
  }

  private async revealEditor(target: {
    readonly absPath?: string
    readonly startLine?: number
    readonly endLine?: number
  } = {}): Promise<void> {
    const current = this.currentChatFile()
    const absPath = target.absPath !== undefined && target.absPath !== ""
      ? target.absPath
      : current?.absPath
    if (absPath === undefined) return
    const uri = vscode.Uri.file(absPath)
    const hasRange = target.startLine !== undefined && target.startLine > 0
    if (!hasRange && current?.fromPlanPreview === true && current.absPath === absPath) {
      try {
        await vscode.commands.executeCommand("markdown.showPreview", uri)
        return
      } catch {
        /* fall through to the text editor */
      }
    }
    const doc = await vscode.workspace.openTextDocument(uri)
    const match = this.matchingEditor(absPath)
    const selection = hasRange && target.startLine !== undefined
      ? lineRange(doc, target.startLine, target.endLine ?? target.startLine)
      : match !== undefined
      ? new vscode.Range(
        match.startLine - 1,
        match.startCol - 1,
        match.endLine - 1,
        match.endCol - 1,
      )
      : undefined
    await vscode.window.showTextDocument(doc, {
      preserveFocus: false,
      preview: false,
      ...(selection !== undefined ? { selection } : {}),
    })
  }

  private activeFile(): PromptChip | undefined {
    const current = this.currentChatFile()
    if (current === undefined) return undefined
    const root = this.workspaceRoot()
    const match = this.matchingEditor(current.absPath)
    const languageId = match?.languageId ?? (current.fromPlanPreview ? "markdown" : undefined)
    return chipFromFile({
      absPath: current.absPath,
      source: "active",
      ...(root !== undefined ? { workspaceRoot: root } : {}),
      ...(languageId !== undefined && languageId !== "" ? { languageId } : {}),
    })
  }

  private handlers(): WebviewHandlers {
    return {
      ready: () => {
        this.syncChangesToast()
        this.postSettings()
        this.postEditorContext()
        void this.ensureAgent()
        void this.refreshMcpCatalog()
      },
      send: (msg: { text: string; chips: ReadonlyArray<PromptChip> }) => {
        void this.sendPrompt(msg.text, msg.chips)
      },
      queue: (msg: { text: string; chips: ReadonlyArray<PromptChip> }) => {
        void this.runtime?.queueFollowUp(msg.text, msg.chips)
      },
      steer: (msg: { text: string; chips: ReadonlyArray<PromptChip> }) => {
        void this.runtime?.queueFollowUp(msg.text, msg.chips)
      },
      cancel: () => {
        void this.cancel()
      },
      permissionChoice: (msg: { requestId: string; optionId: string }) => {
        this.runtime?.permissionChoice(msg.requestId, msg.optionId)
      },
      permissionPark: (msg: { requestId: string }) => {
        this.runtime?.permissionPark(msg.requestId)
      },
      openDiff: (msg: { requestId: string }) => {
        this.runtime?.openDiff(msg.requestId)
      },
      revealEditor: (msg: { absPath?: string; startLine?: number; endLine?: number }) => {
        void this.revealEditor(msg)
      },
      addSelection: () => {
        this.addSelectionToChat()
      },
      openPlan: (msg: { markdown?: string }) => {
        void this.openPlan(msg.markdown)
      },
      planVerdict: (msg) => {
        this.runtime?.planVerdict(
          msg.requestId,
          msg.verdict,
          ...(msg.comment !== undefined ? [msg.comment] : []),
        )
      },
      questionChoice: (msg) => {
        this.runtime?.questionChoice(msg.requestId, msg.answers)
      },
      questionDismiss: (msg: { requestId: string }) => {
        this.runtime?.questionDismiss(msg.requestId)
      },
      questionPark: (msg: { requestId: string }) => {
        this.runtime?.questionDismiss(msg.requestId)
      },
      elicitAccept: (msg: { requestId: string }) => {
        this.runtime?.elicitAccept(msg.requestId)
      },
      elicitDecline: (msg: { requestId: string }) => {
        this.runtime?.elicitDecline(msg.requestId)
      },
      slashPick: (_msg) => undefined,
      mentionQuery: (msg: { query: string }) => {
        void this.runtime?.mentionQuery(msg.query)
      },
      mentionPick: (msg: { path: string; absPath: string }) => {
        this.runtime?.mentionPick(msg.path, msg.absPath)
      },
      cycleMode: () => {
        void this.cycleMode()
      },
      setMode: (msg: { modeId: string }) => {
        void this.setMode(msg.modeId)
      },
      setModel: (msg: { modelId: string }) => {
        void this.setModel(msg.modelId)
      },
      sendNow: () => {
        void this.runtime?.sendQueuedNow()
      },
      setReasoning: (msg: { value: string; modelId?: string }) => {
        void this.runtime?.setReasoning(msg.value, msg.modelId)
      },
      openSettings: () => {
        this.postSettings()
        void this.refreshMcpCatalog()
      },
      openSettingsJson: () => {
        void vscode.commands.executeCommand("workbench.action.openSettingsJson")
      },
      setSetting: (msg: { key: string; value: string | boolean }) => {
        void this.applySetting(msg.key, msg.value)
      },
      openChanges: () => {
        this.openPendingChanges()
      },
      keepAllPending: () => {
        this.review.store.keepEvery()
      },
      undoAllPending: () => {
        void this.review.store.undoEvery(vscodeUndoPorts()).then(reportUndoResult)
      },
      commitAllPending: () => {
        void vscode.commands.executeCommand("groksBeard.commitChanges")
      },
      refreshMcp: (msg: { name: string }) => {
        void this.refreshLiveMcp(msg.name)
      },
      setMcpEnabled: (msg: { name: string; enabled: boolean }) => {
        void this.setMcpEnabled(msg.name, msg.enabled)
      },
      setMcpToolEnabled: (msg: { name: string; tool: string; enabled: boolean }) => {
        void this.setMcpToolEnabled(msg.name, msg.tool, msg.enabled)
      },
      openMcpConfig: () => {
        void this.openMcpConfig()
      },
      trustFolder: () => {
        void this.trustFolder()
      },
    }
  }

  private async refreshMcpCatalog(): Promise<void> {
    const epoch = ++this.mcpEpoch
    this.post({
      _tag: "mcpCatalog",
      loading: true,
      healthyCount: 0,
      failingCount: 0,
      servers: [],
    })
    return this.runMcpDoctor(epoch)
  }

  private async runMcpDoctor(epoch: number): Promise<void> {
    try {
      const command = await this.locateCli()
      const report = await loadMcpCatalog(command, this.workspaceRoot() ?? process.cwd(), {
        trustFolder: this.trustGrokFolder(),
      })
      if (epoch !== this.mcpEpoch) return
      this.postMcpCatalog(report)
      void this.maybeAskFolderTrust(report)
      try {
        await this.ensureAgent()
        await this.runtime?.ensureSession()
      } catch {
        return
      }
      if (epoch !== this.mcpEpoch) return
      this.postMcpCatalog(await this.overlayLiveMcpTools(this.lastMcpReport ?? report))
    } catch (cause) {
      if (epoch !== this.mcpEpoch) return
      const message = cause instanceof Error ? cause.message : String(cause)
      this.post({
        _tag: "mcpCatalog",
        loading: false,
        healthyCount: 0,
        failingCount: 0,
        servers: [],
        error: message,
      })
    }
  }

  private postMcpCatalog(report: McpDoctorReport): void {
    this.lastMcpReport = report
    this.post({
      _tag: "mcpCatalog",
      loading: false,
      healthyCount: report.healthyCount,
      failingCount: report.failingCount,
      servers: report.servers.map((server) => ({
        name: server.name,
        transport: server.transport,
        source: server.source,
        healthy: server.healthy,
        ...(server.toolCount !== undefined ? { toolCount: server.toolCount } : {}),
        ...(server.tools !== undefined && server.tools.length > 0
          ? {
            tools: server.tools.map((tool) => ({
              name: tool.name,
              enabled: tool.enabled,
              ...(tool.description !== undefined ? { description: tool.description } : {}),
            })),
          }
          : {}),
        checks: server.checks.map((check) => ({
          label: check.label,
          passed: check.passed,
          ...(check.detail !== undefined ? { detail: check.detail } : {}),
          ...(check.hint !== undefined ? { hint: check.hint } : {}),
        })),
      })),
    })
  }

  private scheduleMcpToolsRefresh(): void {
    if (this.mcpToolsTimer !== undefined) clearTimeout(this.mcpToolsTimer)
    this.mcpToolsTimer = setTimeout(() => {
      this.mcpToolsTimer = undefined
      void this.refreshLiveMcpTools()
    }, 150)
  }

  private async refreshLiveMcpTools(): Promise<void> {
    if (this.lastMcpReport === undefined) return
    this.postMcpCatalog(await this.overlayLiveMcpTools(this.lastMcpReport))
  }

  private async overlayLiveMcpTools(report: McpDoctorReport): Promise<McpDoctorReport> {
    const sessionId = this.runtime?.sessionId
    const agent = this.spawned?.beard.agent
    if (sessionId === undefined || agent === undefined) return report
    try {
      return overlayMcpTools(report, parseMcpListServers(await listSessionMcp(agent, sessionId)))
    } catch {
      return report
    }
  }

  private async setMcpEnabled(name: string, enabled: boolean): Promise<void> {
    try {
      const command = await this.locateCli()
      await setMcpServerEnabled(command, this.workspaceRoot() ?? process.cwd(), name, enabled)
      await this.refreshMcpCatalog()
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      this.post({ _tag: "error", message })
    }
  }

  private async setMcpToolEnabled(name: string, tool: string, enabled: boolean): Promise<void> {
    try {
      await this.ensureAgent()
      const sessionId = await this.runtime?.ensureSession()
      const agent = this.spawned?.beard.agent
      if (sessionId === undefined || agent === undefined) {
        throw new Error("No Grok session")
      }
      await toggleSessionMcpTool(agent, sessionId, name, tool, enabled)
      const current = this.lastMcpReport
      if (current !== undefined) {
        const previous = current.servers.find((server) => server.name === name)?.tools ?? []
        const tools = previous.length > 0
          ? previous.map((row) => row.name === tool ? { ...row, enabled } : row)
          : [{ name: tool, enabled }]
        this.postMcpCatalog(overlayMcpTools(current, [{ name, tools }]))
      }
      if (this.lastMcpReport !== undefined) {
        this.postMcpCatalog(await this.overlayLiveMcpTools(this.lastMcpReport))
      }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      this.post({ _tag: "error", message })
    }
  }

  private postSettings(): void {
    const config = vscode.workspace.getConfiguration("groksBeard")
    const presentation = changesPresentationFrom(config.get("changesPresentation"))
    this.post({
      _tag: "settingsState",
      cliPath: config.get<string>("cliPath") ?? "",
      nodePath: config.get<string>("nodePath") ?? "",
      includeActiveFileByDefault: config.get<boolean>("includeActiveFileByDefault") ?? true,
      useCtrlEnterToSend: config.get<boolean>("useCtrlEnterToSend") ?? false,
      changesPresentation: presentation,
    })
  }

  private async applySetting(key: string, value: string | boolean): Promise<void> {
    const config = vscode.workspace.getConfiguration("groksBeard")
    if (key === "cliPath" || key === "nodePath") {
      if (typeof value === "string") {
        await config.update(key, value, vscode.ConfigurationTarget.Global)
      }
      return
    }
    if (key === "includeActiveFileByDefault" || key === "useCtrlEnterToSend") {
      if (typeof value === "boolean") {
        await config.update(key, value, vscode.ConfigurationTarget.Global)
      }
      return
    }
    if (key === "changesPresentation" && (value === "toast" || value === "pane")) {
      await config.update(key, value, vscode.ConfigurationTarget.Global)
    }
  }

  private async maybeAskFolderTrust(report: McpDoctorReport): Promise<void> {
    if (!reportNeedsFolderTrust(report) || this.grokTrustGranted || this.trustPromptShown) return
    const root = this.workspaceRoot() ?? process.cwd()
    if (this.context.workspaceState.get(folderTrustDismissKey(root)) === true) return
    this.trustPromptShown = true
    const choice = await vscode.window.showWarningMessage(
      folderTrustPromptMessage(root, untrustedServerNames(report)),
      { modal: true },
      "Trust",
      "Not now",
    )
    if (choice === "Trust") {
      await this.grantFolderTrustAndReload()
      return
    }
    if (choice === "Not now") {
      await this.context.workspaceState.update(folderTrustDismissKey(root), true)
    }
  }

  private async trustFolder(): Promise<void> {
    const root = this.workspaceRoot() ?? process.cwd()
    const choice = await vscode.window.showWarningMessage(
      folderTrustPromptMessage(root, []),
      { modal: true },
      "Trust",
    )
    if (choice !== "Trust") return
    await this.grantFolderTrustAndReload()
  }

  private async grantFolderTrustAndReload(): Promise<void> {
    const root = this.workspaceRoot() ?? process.cwd()
    try {
      const command = await this.locateCli()
      await grantFolderTrust(command, root)
      this.grokTrustGranted = true
      await this.context.workspaceState.update(folderTrustDismissKey(root), undefined)
      await this.restartAgent()
      await this.refreshMcpCatalog()
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      this.post({ _tag: "error", message })
    }
  }

  private async restartAgent(): Promise<void> {
    if (this.starting !== undefined) {
      await this.starting.catch(() => undefined)
    }
    if (this.spawned !== undefined) {
      killSpawnedAgent(this.spawned)
      this.spawned = undefined
    }
    this.runtime = undefined
    this.post({ _tag: "clearTranscript" })
    await this.ensureAgent()
  }

  private async openPlan(markdown?: string): Promise<void> {
    if (markdown !== undefined && markdown !== "") this.lastPlanMarkdown = markdown
    const body = markdown !== undefined && markdown !== "" ? markdown : this.lastPlanMarkdown
    const resolved = resolvePlanFile({
      home: grokHome(process.env as Record<string, string | undefined>),
      cwd: this.workspaceRoot() ?? process.cwd(),
      ...(this.runtime?.sessionId !== undefined ? { sessionId: this.runtime.sessionId } : {}),
      tmpDir: tmpdir(),
      exists: existsSync,
    })
    try {
      if (!resolved.fromSession) {
        if (body === "") {
          this.post({ _tag: "error", message: "No plan markdown to open yet." })
          return
        }
        writeFileSync(resolved.path, body, "utf8")
      }
      const uri = vscode.Uri.file(resolved.path)
      this.planPreviewPath = resolved.path
      try {
        await vscode.commands.executeCommand("markdown.showPreview", uri)
        this.postEditorContext()
      } catch {
        const doc = await vscode.workspace.openTextDocument(uri)
        await vscode.window.showTextDocument(doc, { preview: true })
        this.postEditorContext()
      }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      this.post({ _tag: "error", message })
    }
  }

  private async openMcpConfig(): Promise<void> {
    const root = this.workspaceRoot()
    const project = root !== undefined ? join(root, ".grok", "config.toml") : undefined
    const user = join(
      grokHome(process.env as Record<string, string | undefined>),
      "config.toml",
    )
    const path = project !== undefined && existsSync(project) ? project : user
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(path))
    await vscode.window.showTextDocument(doc, { preview: false })
  }

  private openPendingChanges(): void {
    const presentation = changesPresentationFrom(
      vscode.workspace.getConfiguration("groksBeard").get("changesPresentation"),
    )
    if (presentation === "pane") {
      void vscode.commands.executeCommand(`${changesViewIdForHost(vscode.env.appName)}.focus`)
      return
    }
    void vscode.commands.executeCommand("groksBeard.openChangesReview")
  }

  private async startAgent(): Promise<void> {
    const cwd = this.workspaceRoot() ?? process.cwd()
    const holder: { runtime?: ChatRuntime } = {}
    try {
      const live = process.env.GROKS_BEARD_FAKE_AGENT !== "1"
      const command = live ? await this.locateCli() : undefined
      const policy = command !== undefined
        ? liveSpawnCapabilityPolicy(
          resolveGrokVersion(await readGrokVersionBanner(command), undefined, undefined),
        )
        : fakeSpawnCapabilityPolicy()
      const spawned = await this.spawn(cwd, holder, command)
      this.spawned = spawned
      const version = String(
        (this.context.extension.packageJSON as { version?: string }).version ?? "0.0.0",
      )
      await this.effectRuntime.runPromise(initializeAgent(spawned.beard.agent, policy, version))
      const runtime = new ChatRuntime({
        agent: spawned.beard.agent,
        post: (msg) => this.post(msg),
        composer: this.composer,
        cwd,
        onMcpIssue: (message) => {
          void this.showMcpIssue(message)
        },
        includeActiveFileByDefault: () =>
          vscode.workspace.getConfiguration("groksBeard").get<boolean>(
            "includeActiveFileByDefault",
          ) ?? true,
        activeFile: () => this.activeFile(),
        searchFiles: (query) => this.searchFiles(query),
        openChanges: () => this.openPendingChanges(),
        openDiff: (requestId) => {
          void this.review.openPermissionDiff(requestId)
        },
        onTurn: (sessionId, turnId, title) => {
          this.review.setTurn(sessionId, turnId, title)
        },
        rememberPermission: (requestId, params) => {
          this.review.rememberPermission(requestId, params)
        },
        ingestUpdate: (params, ctx) => {
          this.review.ingestUpdate(params, ctx)
        },
        onPermissionChoice: (requestId, optionId) => {
          this.review.onPermissionChoice(requestId, optionId)
        },
        onCancelPermissions: () => {
          this.review.cancelPendingPermissions()
        },
        modelCatalog: () => readGrokModelCatalog(),
      })
      holder.runtime = runtime
      this.runtime = runtime
      await runtime.ensureSession()
    } catch (cause) {
      const searched = cause && typeof cause === "object" && "searched" in cause
        ? (cause as { searched: ReadonlyArray<string> }).searched
        : ["PATH"]
      this.post({
        _tag: "error",
        message: missingCliMessage(searched),
      })
    }
  }

  private async locateCli(): Promise<string> {
    const cliPath = vscode.workspace.getConfiguration("groksBeard").get<string>("cliPath") ?? ""
    return Effect.runPromise(locateGrokCli({
      ...(cliPath !== "" ? { cliPath } : {}),
      env: process.env as Record<string, string | undefined>,
      exists: existsSync,
      win: process.platform === "win32",
    }))
  }

  private async sendPrompt(text: string, chips: ReadonlyArray<PromptChip>): Promise<void> {
    await this.ensureAgent()
    const action = await this.runtime?.prepareMcpForTurn() ?? "ok"
    if (action === "respawn") await this.restartAgent()
    this.runtime?.send(text, chips)
  }

  private async refreshLiveMcp(name: string): Promise<void> {
    await this.ensureAgent()
    const action = await this.runtime?.refreshMcp(name) ?? "ok"
    if (action === "respawn") await this.restartAgent()
    await this.refreshMcpServer(name)
  }

  private async refreshMcpServer(name: string): Promise<void> {
    try {
      const command = await this.locateCli()
      const report = await loadMcpCatalog(command, this.workspaceRoot() ?? process.cwd(), {
        trustFolder: this.trustGrokFolder(),
        name,
      })
      const server = report.servers.find((row) => row.name === name) ?? report.servers[0]
      if (server === undefined) {
        await this.refreshMcpCatalog()
        return
      }
      const merged = mergeDoctorServer(
        this.lastMcpReport ?? { servers: [], healthyCount: 0, failingCount: 0 },
        server,
      )
      this.postMcpCatalog(await this.overlayLiveMcpTools(merged))
    } catch {
      await this.refreshMcpCatalog()
    }
  }

  private async showMcpIssue(message: string): Promise<void> {
    await vscode.window.showWarningMessage(message)
  }

  private async spawn(
    cwd: string,
    holder: { runtime?: ChatRuntime },
    command: string | undefined,
  ): Promise<SpawnedAgent> {
    const handlers = {
      onSessionUpdate: (params: unknown) => holder.runtime?.onSessionUpdate(params),
      onMcpCatalogChanged: () => {
        this.scheduleMcpToolsRefresh()
      },
      onPermission: (params: unknown, requestId: string) =>
        holder.runtime?.onPermission(params, requestId) ?? {
          outcome: { outcome: "cancelled" as const },
        },
      onExitPlanMode: (params: unknown, requestId: string) =>
        holder.runtime?.onExitPlanMode(params, requestId) ?? { outcome: "cancelled" as const },
      onAskUserQuestion: (params: unknown, requestId: string) =>
        holder.runtime?.onAskUserQuestion(params, requestId) ?? { answers: [] },
      onElicit: (params: unknown, requestId: string) =>
        holder.runtime?.onElicit(params, requestId) ?? { action: "cancel" as const },
    }
    if (command === undefined) {
      const fixture = join(
        dirname(fileURLToPath(import.meta.url)),
        "../../acp/test/fixtures/fake-grok.mjs",
      )
      return spawnGrokAgentStdio({
        command: process.execPath,
        args: [fixture, ...GROK_AGENT_STDIO_ARGS],
        cwd,
        ...handlers,
      })
    }
    return spawnGrokAgentStdio({
      command,
      cwd,
      args: [...grokAgentStdioArgs(this.trustGrokFolder())],
      terminal: createHostTerminalManager(cwd),
      ...handlers,
    })
  }

  private async searchFiles(
    query: string,
  ): Promise<ReadonlyArray<{ path: string; absPath: string }>> {
    const pattern = mentionFilePattern(query)
    if (pattern === undefined) return []
    const uris = await vscode.workspace.findFiles(pattern, mentionFileExclude, MENTION_FILE_LIMIT)
    const root = this.workspaceRoot()
    const files = uris.map((uri) => ({
      absPath: uri.fsPath,
      path: root !== undefined && uri.fsPath.startsWith(`${root}/`)
        ? uri.fsPath.slice(root.length + 1)
        : uri.fsPath,
    }))
    return rankMentionFiles(files, query)
  }
}
