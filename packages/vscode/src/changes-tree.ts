import * as vscode from "vscode"

export class ChangesTreeProvider implements vscode.TreeDataProvider<string> {
  static readonly viewId = "groksBeard.changes"

  getTreeItem(element: string): vscode.TreeItem {
    return new vscode.TreeItem(element)
  }

  getChildren(): Array<string> {
    return []
  }
}
