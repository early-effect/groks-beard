import {
  type ChangeSetRecord,
  changeSetLineStats,
  type FileChangeRecord,
  formatLineStats
} from "@groks-beard/core"
import * as vscode from "vscode"
import type { ChangeStore } from "./change-store.js"

export type ChangesNode =
  | { readonly type: "turn"; readonly sessionId: string; readonly turnId: string }
  | {
    readonly type: "file"
    readonly sessionId: string
    readonly turnId: string
    readonly path: string
  }

export const turnNodeId = (sessionId: string, turnId: string): string =>
  `${sessionId}::${turnId}`

export const fileNodeId = (sessionId: string, turnId: string, path: string): string =>
  `${sessionId}::${turnId}::${encodeURIComponent(path)}`

export const parseChangesNode = (id: string): ChangesNode | undefined => {
  const parts = id.split("::")
  if (parts.length === 2 && parts[0] !== undefined && parts[1] !== undefined) {
    return { type: "turn", sessionId: parts[0], turnId: parts[1] }
  }
  if (parts.length >= 3 && parts[0] !== undefined && parts[1] !== undefined) {
    return {
      type: "file",
      sessionId: parts[0],
      turnId: parts[1],
      path: decodeURIComponent(parts.slice(2).join("::"))
    }
  }
  return undefined
}

const fileName = (path: string): string => {
  const parts = path.split(/[\\/]/)
  return parts[parts.length - 1] ?? path
}

export const turnDescription = (set: ChangeSetRecord): string => {
  const stats = changeSetLineStats(set.files)
  return formatLineStats(stats.additions, stats.deletions)
}

export const fileDescription = (file: FileChangeRecord): string => {
  const stats = formatLineStats(file.additions, file.deletions)
  const region = file.wholeFile ? "" : " region"
  const reason = file.undoDisabledReason !== undefined ? ` ${file.undoDisabledReason}` : ""
  return `${file.kind} ${stats}${region}${reason}`
}

export class ChangesTreeProvider implements vscode.TreeDataProvider<string> {
  static readonly viewId = "groksBeard.changes"

  private readonly emitter = new vscode.EventEmitter<string | undefined>()
  readonly onDidChangeTreeData = this.emitter.event

  constructor(private readonly store: ChangeStore) {
    store.onChange(() => this.emitter.fire(undefined))
  }

  refresh(): void {
    this.emitter.fire(undefined)
  }

  getTreeItem(element: string): vscode.TreeItem {
    const node = parseChangesNode(element)
    if (node === undefined) return new vscode.TreeItem(element)
    if (node.type === "turn") {
      const set = this.store.getTurn(node.sessionId, node.turnId)
      const item = new vscode.TreeItem(
        set?.title ?? node.turnId,
        vscode.TreeItemCollapsibleState.Expanded
      )
      item.id = element
      item.contextValue = "changeTurn"
      if (set !== undefined) item.description = turnDescription(set)
      return item
    }
    const file = this.store.getFile(node.sessionId, node.turnId, node.path)
    const item = new vscode.TreeItem(fileName(node.path), vscode.TreeItemCollapsibleState.None)
    item.id = element
    item.resourceUri = vscode.Uri.file(node.path)
    item.contextValue = file?.undoDisabledReason !== undefined ? "changeFileNoUndo" : "changeFile"
    if (file !== undefined) item.description = fileDescription(file)
    item.tooltip = node.path
    item.command = {
      command: "groksBeard.openChangeDiff",
      title: "Open Diff",
      arguments: [element]
    }
    return item
  }

  getChildren(element?: string): Array<string> {
    if (element === undefined) {
      return this.store.list().map((set) => turnNodeId(set.sessionId, set.turnId))
    }
    const node = parseChangesNode(element)
    if (node?.type !== "turn") return []
    const set = this.store.getTurn(node.sessionId, node.turnId)
    if (set === undefined) return []
    return set.files.map((file) => fileNodeId(node.sessionId, node.turnId, file.path))
  }
}
