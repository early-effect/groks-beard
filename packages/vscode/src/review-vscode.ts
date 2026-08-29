import { ChangeSetIndex } from "@groks-beard/core"
import { Schema } from "effect"
import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import * as vscode from "vscode"
import { CHANGE_INDEX_KEY, ChangeStore, type UndoApplyPorts } from "./change-store.js"
import type { DiffOpenPlan } from "./diff-open.js"
import { detectDiffEditor, type FollowAlongPlan, schemesFromTabInput } from "./follow-along.js"
import { gitHeadText } from "./path-diff.js"
import { ReviewHost } from "./review-host.js"
import { BeardDocStore, ORIGINAL_SCHEME, PROPOSED_SCHEME, virtualDocRef } from "./virtual-docs.js"

export class BeardContentProvider implements vscode.TextDocumentContentProvider {
  readonly onDidChange: vscode.Event<vscode.Uri>
  private readonly emitter = new vscode.EventEmitter<vscode.Uri>()

  constructor(private readonly docs: BeardDocStore) {
    this.onDidChange = this.emitter.event
    docs.onDidChange((scheme, absPath) => {
      this.emitter.fire(vscode.Uri.from(virtualDocRef(scheme, absPath)))
    })
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this.docs.get(uri.scheme, uri.path)
  }
}

export const readDiskText = (absPath: string): string | undefined => {
  try {
    return readFileSync(absPath, "utf8")
  } catch {
    return undefined
  }
}

const uriFromRef = (ref: { readonly scheme: string; readonly path: string }): vscode.Uri =>
  vscode.Uri.from({ scheme: ref.scheme, path: ref.path })

export const executeDiffPlan = async (plan: DiffOpenPlan): Promise<void> => {
  if (plan.mode === "multi") {
    const triples = plan.files.map((file) => [
      vscode.Uri.file(file.path),
      uriFromRef(file.original),
      uriFromRef(file.proposed),
    ])
    await vscode.commands.executeCommand("vscode.changes", plan.title, triples)
    return
  }
  for (const file of plan.files) {
    await vscode.commands.executeCommand(
      "vscode.diff",
      uriFromRef(file.original),
      uriFromRef(file.proposed),
      `${plan.title}: ${file.path}`,
    )
  }
}

export const applyFollowAlong = async (plan: FollowAlongPlan): Promise<void> => {
  for (const reveal of plan.reveals) {
    const uri = vscode.Uri.file(reveal.path)
    const line = reveal.line !== undefined ? Math.max(0, reveal.line - 1) : 0
    const selection = new vscode.Range(line, 0, line, 0)
    await vscode.window.showTextDocument(uri, {
      preserveFocus: plan.preserveFocus,
      preview: true,
      selection,
    })
  }
}

const currentText = (path: string): string | undefined => {
  const uri = vscode.Uri.file(path)
  const open = vscode.workspace.textDocuments.find((doc) => doc.uri.fsPath === uri.fsPath)
  if (open !== undefined) return open.getText()
  return readDiskText(path)
}

export const vscodeUndoPorts = (): UndoApplyPorts => ({
  readDisk: (path) => currentText(path),
  confirmDirty: async (path) => {
    const pick = await vscode.window.showWarningMessage(
      `${path} differs from the agent snapshot. Delete anyway?`,
      { modal: true },
      "Delete",
    )
    return pick === "Delete"
  },
  apply: async (mutations) => {
    const edit = new vscode.WorkspaceEdit()
    for (const mutation of mutations) {
      const uri = vscode.Uri.file(mutation.path)
      if (mutation._tag === "delete") {
        edit.deleteFile(uri, { ignoreIfNotExists: true })
        continue
      }
      if (mutation._tag === "create") {
        edit.createFile(uri, { overwrite: true, contents: Buffer.from(mutation.text, "utf8") })
        continue
      }
      const open = vscode.workspace.textDocuments.find((doc) => doc.uri.fsPath === uri.fsPath)
      if (open !== undefined) {
        const full = new vscode.Range(open.positionAt(0), open.positionAt(open.getText().length))
        edit.replace(uri, full, mutation.text)
      } else {
        try {
          const doc = await vscode.workspace.openTextDocument(uri)
          const full = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length))
          edit.replace(uri, full, mutation.text)
        } catch {
          edit.createFile(uri, { overwrite: true, contents: Buffer.from(mutation.text, "utf8") })
        }
      }
    }
    const ok = await vscode.workspace.applyEdit(edit)
    if (!ok) throw new Error("Workspace edit was not applied")
  },
})

export const createReviewHost = (context: vscode.ExtensionContext): {
  readonly docs: BeardDocStore
  readonly store: ChangeStore
  readonly review: ReviewHost
  readonly provider: BeardContentProvider
} => {
  const docs = new BeardDocStore()
  const storageRoot = (context.storageUri ?? context.globalStorageUri)?.fsPath ?? join(
    context.extensionPath,
    ".beard-changes",
  )
  try {
    mkdirSync(storageRoot, { recursive: true })
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    void vscode.window.showWarningMessage(`Grok Changes storage is not writable: ${message}`)
  }
  const store = new ChangeStore({
    storageRoot,
    now: () => Date.now(),
    join,
    loadIndex: () => context.workspaceState.get(CHANGE_INDEX_KEY),
    saveIndex: (index) => {
      void context.workspaceState.update(CHANGE_INDEX_KEY, Schema.encodeSync(ChangeSetIndex)(index))
    },
    fs: {
      read: readDiskText,
      write: (absPath, text) => {
        mkdirSync(dirname(absPath), { recursive: true })
        writeFileSync(absPath, text, "utf8")
      },
      remove: (absPath) => {
        try {
          unlinkSync(absPath)
        } catch {
          return
        }
      },
      mkdirp: (absPath) => {
        mkdirSync(absPath, { recursive: true })
      },
    },
  })
  const review = new ReviewHost(docs, store, {
    readDisk: readDiskText,
    hasChangesCommand: async () => {
      const commands = await vscode.commands.getCommands(true)
      return commands.includes("vscode.changes")
    },
    openDiffs: executeDiffPlan,
    follow: (plan) => {
      void applyFollowAlong(plan)
    },
    warn: (message) => {
      void vscode.window.showWarningMessage(message)
    },
    gitHead: (path) => {
      const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath
      if (root === undefined) return undefined
      return gitHeadText(root, path)
    },
    activeScheme: () => vscode.window.activeTextEditor?.document.uri.scheme,
    inDiffEditor: () => {
      const group = vscode.window.tabGroups.activeTabGroup
      const schemes = group.tabs.flatMap((tab) => schemesFromTabInput(tab.input))
      const editor = vscode.window.activeTextEditor
      return detectDiffEditor({
        tabInput: group.activeTab?.input,
        schemesInActiveGroup: schemes,
        ...(editor !== undefined ? { scheme: editor.document.uri.scheme } : {}),
      })
    },
  })
  const provider = new BeardContentProvider(docs)
  return { docs, store, review, provider }
}

export const registerVirtualDocs = (
  context: vscode.ExtensionContext,
  provider: BeardContentProvider,
): void => {
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(ORIGINAL_SCHEME, provider),
    vscode.workspace.registerTextDocumentContentProvider(PROPOSED_SCHEME, provider),
  )
}
