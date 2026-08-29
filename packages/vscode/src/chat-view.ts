import {
  fakeSpawnCapabilityPolicy,
  GROK_AGENT_STDIO_ARGS,
  initializeAgent,
  killSpawnedAgent,
  liveSpawnCapabilityPolicy,
  readGrokVersionBanner,
  resolveGrokVersion,
  type SpawnedAgent,
  spawnGrokAgentStdio,
} from "@groks-beard/acp"
import { chipFromFile, decodeWebviewMsg, type HostMsg, type PromptChip } from "@groks-beard/core"
import { Effect, type ManagedRuntime } from "effect"
import { existsSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import * as vscode from "vscode"
import { chatHtml } from "./chat-html.js"
import { ChatRuntime } from "./chat-runtime.js"
import { locateGrokCli } from "./cli-locator.js"
import { ComposerState } from "./composer.js"
import { dispatchWebviewMsg, type WebviewHandlers } from "./host-dispatch.js"
import { missingCliMessage } from "./onboarding.js"
import type { ReviewHost } from "./review-host.js"
import { createHostTerminalManager } from "./terminal-manager.js"

export class ChatViewProvider implements vscode.WebviewViewProvider {
  static readonly viewId = "groksBeard.chat"

  private view: vscode.WebviewView | undefined
  private runtime: ChatRuntime | undefined
  private spawned: SpawnedAgent | undefined
  private starting: Promise<void> | undefined
  private readonly pending: Array<HostMsg> = []

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly composer: ComposerState,
    private readonly effectRuntime: ManagedRuntime.ManagedRuntime<never, never>,
    private readonly review: ReviewHost,
  ) {}

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
    const ctrlEnter = vscode.workspace.getConfiguration("groksBeard").get<boolean>(
      "useCtrlEnterToSend",
    ) ?? false
    webview.html = chatHtml({
      cspSource: webview.cspSource,
      scriptUri: scriptUri.toString(),
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
    void this.ensureAgent()
  }

  dispose(): void {
    if (this.spawned !== undefined) killSpawnedAgent(this.spawned)
    this.spawned = undefined
    this.runtime = undefined
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

  private post(msg: HostMsg): void {
    if (this.view === undefined) {
      this.pending.push(msg)
      return
    }
    void this.view.webview.postMessage(msg)
  }

  private workspaceRoot(): string | undefined {
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath
  }

  private activeFile(): PromptChip | undefined {
    const editor = vscode.window.activeTextEditor
    if (editor === undefined) return undefined
    const root = this.workspaceRoot()
    const languageId = editor.document.languageId
    return chipFromFile({
      absPath: editor.document.uri.fsPath,
      source: "active",
      ...(root !== undefined ? { workspaceRoot: root } : {}),
      ...(languageId !== "" ? { languageId } : {}),
    })
  }

  private handlers(): WebviewHandlers {
    return {
      ready: () => {
        void this.ensureAgent()
      },
      send: (msg: { text: string; chips: ReadonlyArray<PromptChip> }) => {
        void this.ensureAgent().then(() => this.runtime?.send(msg.text, msg.chips))
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
      openChanges: (msg: { turnId?: string }) => {
        this.runtime?.openChanges(msg.turnId)
        void vscode.commands.executeCommand("groksBeard.changes.focus")
      },
    }
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
        includeActiveFileByDefault: () =>
          vscode.workspace.getConfiguration("groksBeard").get<boolean>(
            "includeActiveFileByDefault",
          ) ?? true,
        activeFile: () => this.activeFile(),
        searchFiles: (query) => this.searchFiles(query),
        openChanges: () => {
          void vscode.commands.executeCommand("groksBeard.changes.focus")
        },
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

  private async spawn(
    cwd: string,
    holder: { runtime?: ChatRuntime },
    command: string | undefined,
  ): Promise<SpawnedAgent> {
    const handlers = {
      onSessionUpdate: (params: unknown) => holder.runtime?.onSessionUpdate(params),
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
      args: [...GROK_AGENT_STDIO_ARGS],
      terminal: createHostTerminalManager(cwd),
      ...handlers,
    })
  }

  private async searchFiles(
    query: string,
  ): Promise<ReadonlyArray<{ path: string; absPath: string }>> {
    const pattern = query === "" ? "**/*" : `**/*${query}*`
    const uris = await vscode.workspace.findFiles(pattern, "**/node_modules/**", 20)
    const root = this.workspaceRoot()
    return uris.map((uri) => ({
      absPath: uri.fsPath,
      path: root !== undefined && uri.fsPath.startsWith(`${root}/`)
        ? uri.fsPath.slice(root.length + 1)
        : uri.fsPath,
    }))
  }
}
