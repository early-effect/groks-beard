import { type PromptChip, type SelectionInput, workspaceRelativePath } from "@groks-beard/core"
import { dispatchMcpTool } from "@groks-beard/mcp"
import { readFileSync } from "node:fs"
import * as vscode from "vscode"
import type { ChangeStore } from "./change-store.js"
import { type DiffOpenPlan, planDiffOpen } from "./diff-open.js"
import { createMcpToolHost, type McpHostPorts } from "./mcp-host.js"
import { gitHeadText } from "./path-diff.js"
import type { ReviewHost } from "./review-host.js"
import { executeDiffPlan } from "./review-vscode.js"

const readRange = (absPath: string, startLine?: number, endLine?: number): string | undefined => {
  try {
    const text = readFileSync(absPath, "utf8")
    if (startLine === undefined || endLine === undefined) return text
    return text.split(/\n/).slice(startLine - 1, endLine).join("\n")
  } catch {
    return undefined
  }
}

const tabPath = (input: unknown): string | undefined => {
  if (input instanceof vscode.TabInputText && input.uri.scheme === "file") return input.uri.fsPath
  if (input instanceof vscode.TabInputNotebook && input.uri.scheme === "file") {
    return input.uri.fsPath
  }
  return undefined
}

export const vscodeMcpPorts = (
  composer: { pendingSelection: PromptChip | undefined },
  review: ReviewHost,
  store: ChangeStore,
  workspaceRoot: () => string | undefined,
  notice: (message: string) => void,
): McpHostPorts => ({
  workspaceRoot,
  pendingSelection: () => composer.pendingSelection,
  liveSelection: (): SelectionInput | undefined => {
    const editor = vscode.window.activeTextEditor
    if (editor === undefined || editor.selection.isEmpty) return undefined
    const sel = editor.selection
    const absPath = editor.document.uri.fsPath
    const languageId = editor.document.languageId
    return {
      path: workspaceRelativePath(absPath, workspaceRoot()),
      absPath,
      startLine: sel.start.line + 1,
      endLine: sel.end.line + 1,
      startCol: sel.start.character + 1,
      endCol: sel.end.character + 1,
      text: editor.document.getText(sel),
      ...(languageId !== "" ? { languageId } : {}),
    }
  },
  pendingText: (chip) => {
    const editor = vscode.window.activeTextEditor
    if (
      editor !== undefined
      && editor.document.uri.fsPath === chip.absPath
      && chip.startLine !== undefined
      && chip.endLine !== undefined
    ) {
      const endLine = Math.min(Math.max(chip.endLine - 1, 0), editor.document.lineCount - 1)
      const start = new vscode.Position(Math.max(chip.startLine - 1, 0), 0)
      const end = editor.document.lineAt(endLine).range.end
      return editor.document.getText(new vscode.Range(start, end))
    }
    return readRange(chip.absPath, chip.startLine, chip.endLine)
  },
  openFiles: () => {
    const tabs: Array<string> = []
    for (const group of vscode.window.tabGroups.all) {
      for (const tab of group.tabs) {
        const path = tabPath(tab.input)
        if (path !== undefined) tabs.push(path)
      }
    }
    const active = vscode.window.activeTextEditor?.document.uri.fsPath
    return { tabs, ...(active !== undefined ? { active } : {}) }
  },
  reveal: async (path, line) => {
    const uri = vscode.Uri.file(path)
    const row = line !== undefined ? Math.max(line - 1, 0) : 0
    await vscode.window.showTextDocument(uri, {
      selection: new vscode.Range(row, 0, row, 0),
    })
  },
  beardSnapshot: (path) => {
    const stored = store.loadStoredByPath(path)
    if (stored === undefined) return undefined
    return { original: stored.original, proposed: stored.proposed }
  },
  gitHead: (path) => {
    const root = workspaceRoot()
    if (root === undefined) return undefined
    return gitHeadText(root, path)
  },
  disk: (path) => {
    const uri = vscode.Uri.file(path)
    const open = vscode.workspace.textDocuments.find((doc) => doc.uri.fsPath === uri.fsPath)
    if (open !== undefined) return open.getText()
    return readRange(path)
  },
  openDiff: async (path, original, proposed) => {
    review.docs.setPair(path, original, proposed)
    const commands = await vscode.commands.getCommands(true)
    const plan: DiffOpenPlan = planDiffOpen(commands.includes("vscode.changes"), path, [path])
    await executeDiffPlan(plan)
  },
  notice,
  showChanges: async (title, files) =>
    store.ingestSidecar({
      ...(title !== undefined ? { title } : {}),
      files,
    }),
})

export const vscodeMcpHandle = (
  composer: { pendingSelection: PromptChip | undefined },
  review: ReviewHost,
  store: ChangeStore,
  workspaceRoot: () => string | undefined,
  notice: (message: string) => void,
) => {
  const host = createMcpToolHost(vscodeMcpPorts(composer, review, store, workspaceRoot, notice))
  return (request: { tool: Parameters<typeof dispatchMcpTool>[0]; args?: unknown }) =>
    dispatchMcpTool(request.tool, request.args ?? {}, host)
}
