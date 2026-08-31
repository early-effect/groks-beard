import { randomBytes } from "node:crypto"
import * as vscode from "vscode"
import type { UndoApplyResult } from "./change-store.js"
import { changesReviewHtml } from "./changes-review-html.js"
import { reviewStatePayload } from "./changes-review-model.js"
import { fileNodeId } from "./changes-tree.js"
import { commitGrokFiles } from "./git-commit-host.js"
import type { ReviewHost } from "./review-host.js"
import { reportUndoResult, vscodeUndoPorts } from "./review-vscode.js"

export {
  displayChangePath,
  reviewStatePayload,
  reviewTurnsFromSets,
} from "./changes-review-model.js"
export type { ReviewFileView, ReviewTurnView } from "./changes-review-model.js"

type ReviewAct = {
  readonly act?: string
  readonly sessionId?: string
  readonly turnId?: string
  readonly path?: string
}

export class ChangesReviewPanel {
  private panel: vscode.WebviewPanel | undefined
  private unsubscribe: (() => void) | undefined

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly review: ReviewHost,
    private readonly workspaceRoot: () => string | undefined,
  ) {}

  reveal(): void {
    if (this.panel !== undefined) {
      this.panel.reveal(vscode.ViewColumn.One)
      this.postState()
      return
    }
    const panel = vscode.window.createWebviewPanel(
      "groksBeard.changesReview",
      "Grok Changes",
      vscode.ViewColumn.One,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [this.context.extensionUri],
      },
    )
    this.panel = panel
    const nonce = randomBytes(16).toString("hex")
    panel.webview.html = changesReviewHtml({
      cspSource: panel.webview.cspSource,
      nonce,
    })
    panel.webview.onDidReceiveMessage((raw: unknown) => {
      void this.onMessage(raw as ReviewAct)
    })
    panel.onDidDispose(() => {
      this.unsubscribe?.()
      this.unsubscribe = undefined
      this.panel = undefined
    })
    this.unsubscribe = this.review.store.onChange(() => this.postState())
    this.postState()
  }

  private postState(): void {
    if (this.panel === undefined) return
    void this.panel.webview.postMessage(
      reviewStatePayload(this.review.store.list(), this.workspaceRoot()),
    )
  }

  private async onMessage(msg: ReviewAct): Promise<void> {
    const act = msg.act
    if (act === "ready") {
      this.postState()
      return
    }
    if (act === "keepEvery") {
      this.review.store.keepEvery()
      return
    }
    if (act === "undoEvery") {
      this.report(await this.review.store.undoEvery(vscodeUndoPorts()))
      return
    }
    if (act === "commitEvery") {
      await this.commitSets(this.review.store.list())
      return
    }
    const sessionId = msg.sessionId
    const turnId = msg.turnId
    if (sessionId === undefined || turnId === undefined) return
    if (act === "keepTurn") {
      this.review.keepAll(sessionId, turnId)
      return
    }
    if (act === "undoTurn") {
      this.report(await this.review.undoAll(sessionId, turnId, vscodeUndoPorts()))
      return
    }
    if (act === "commitTurn") {
      const set = this.review.store.getTurn(sessionId, turnId)
      if (set !== undefined) await this.commitSets([set])
      return
    }
    const path = msg.path
    if (path === undefined) return
    if (act === "open") {
      void vscode.commands.executeCommand(
        "groksBeard.openChangeDiff",
        fileNodeId(sessionId, turnId, path),
      )
      return
    }
    if (act === "keep") {
      this.review.keep(sessionId, turnId, path)
      return
    }
    if (act === "undo") {
      this.report(await this.review.undo(sessionId, turnId, path, vscodeUndoPorts()))
      return
    }
    if (act === "commit") {
      const set = this.review.store.getTurn(sessionId, turnId)
      if (set === undefined) return
      await this.commitSets([set], path)
    }
  }

  private async commitSets(
    sets: ReadonlyArray<{
      readonly sessionId: string
      readonly turnId: string
      readonly title: string
      readonly files: ReadonlyArray<{ readonly path: string }>
    }>,
    onlyPath?: string,
  ): Promise<void> {
    const files = sets.flatMap((set) =>
      set.files.filter((file) => onlyPath === undefined || file.path === onlyPath)
    )
    try {
      const count = await commitGrokFiles(
        files.map((file) => file.path),
        sets.map((set) => set.title),
      )
      if (count === undefined) return
      if (onlyPath !== undefined) {
        const set = sets[0]
        if (set !== undefined) this.review.keep(set.sessionId, set.turnId, onlyPath)
      } else {
        for (const set of sets) this.review.keepAll(set.sessionId, set.turnId)
      }
      void vscode.window.showInformationMessage(
        count === 1 ? "Committed 1 file." : `Committed ${count} files.`,
      )
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause)
      void vscode.window.showErrorMessage(`Commit failed: ${message}`)
    }
  }

  private report(result: UndoApplyResult): void {
    reportUndoResult(result)
  }
}
