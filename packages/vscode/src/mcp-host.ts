import {
  type EditorOpenDiffArgs,
  EditorOpenDiffResult,
  type EditorOpenFilesArgs,
  type EditorRevealArgs,
  EditorRevealResult,
  type EditorShowChangesArgs,
  EditorShowChangesResult,
  EditorWorkspaceRootResult,
  pageOpenFiles,
  preferPendingSelection,
  type PromptChip,
  type SelectionInput,
} from "@groks-beard/core"
import type { McpToolHost } from "@groks-beard/mcp"
import { isAbsolute, join } from "node:path"
import { resolvePathDiff } from "./path-diff.js"

export type McpHostPorts = {
  readonly workspaceRoot: () => string | undefined
  readonly pendingSelection: () => PromptChip | undefined
  readonly liveSelection: () => SelectionInput | undefined
  readonly pendingText: (chip: PromptChip) => string | undefined
  readonly openFiles: () => { readonly tabs: ReadonlyArray<string>; readonly active?: string }
  readonly reveal: (path: string, line?: number) => Promise<void>
  readonly beardSnapshot: (
    path: string,
  ) => { readonly original: string; readonly proposed: string } | undefined
  readonly gitHead: (path: string) => string | undefined
  readonly disk: (path: string) => string | undefined
  readonly openDiff: (
    path: string,
    original: string,
    proposed: string,
    line?: number,
  ) => Promise<void>
  readonly notice: (message: string) => void
  readonly showChanges: (
    title: string | undefined,
    files: ReadonlyArray<
      { readonly path: string; readonly kind: EditorShowChangesArgs["files"][number]["kind"] }
    >,
  ) => Promise<number>
}

export const resolveToolPath = (workspaceRoot: string | undefined, path: string): string => {
  if (isAbsolute(path) || workspaceRoot === undefined || workspaceRoot === "") return path
  return join(workspaceRoot, path)
}

export const createMcpToolHost = (ports: McpHostPorts): McpToolHost => ({
  workspaceRoot: async () => new EditorWorkspaceRootResult({ root: ports.workspaceRoot() ?? "" }),
  selection: async () => {
    const pending = ports.pendingSelection()
    const live = ports.liveSelection()
    const pendingText = pending !== undefined ? ports.pendingText(pending) : undefined
    return preferPendingSelection(
      pending,
      live,
      pendingText ?? (pending !== undefined ? live?.text : undefined),
    )
  },
  openFiles: async (args: EditorOpenFilesArgs) => {
    const files = ports.openFiles()
    return pageOpenFiles({
      tabs: files.tabs,
      ...(files.active !== undefined ? { active: files.active } : {}),
      ...(args.cursor !== undefined ? { cursor: args.cursor } : {}),
    })
  },
  reveal: async (args: EditorRevealArgs) => {
    const path = resolveToolPath(ports.workspaceRoot(), args.path)
    await ports.reveal(path, args.line)
    return new EditorRevealResult({ ok: true })
  },
  openDiff: async (args: EditorOpenDiffArgs) => {
    const path = resolveToolPath(ports.workspaceRoot(), args.path)
    const beard = ports.beardSnapshot(path)
    const gitHead = ports.gitHead(path)
    const disk = ports.disk(path)
    const resolved = resolvePathDiff({
      path,
      ...(beard !== undefined ? { beard } : {}),
      ...(gitHead !== undefined ? { gitHead } : {}),
      ...(disk !== undefined ? { disk } : {}),
    })
    if (!resolved.ok) return new EditorOpenDiffResult({ ok: false, reason: resolved.reason })
    if (args.line !== undefined) {
      await ports.openDiff(resolved.path, resolved.original, resolved.proposed, args.line)
    } else {
      await ports.openDiff(resolved.path, resolved.original, resolved.proposed)
    }
    if (resolved.notice !== undefined) ports.notice(resolved.notice)
    return new EditorOpenDiffResult({ ok: true })
  },
  showChanges: async (args: EditorShowChangesArgs) => {
    const root = ports.workspaceRoot()
    const files = args.files.map((file) => ({
      path: resolveToolPath(root, file.path),
      kind: file.kind,
    }))
    const shown = await ports.showChanges(args.title, files)
    return new EditorShowChangesResult({ ok: true, shown })
  },
})
