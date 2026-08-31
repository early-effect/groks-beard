import { ORIGINAL_SCHEME, PROPOSED_SCHEME, virtualDocRef } from "./virtual-docs.js"

export type DiffOpenPlan = {
  readonly mode: "multi" | "pairwise"
  readonly title: string
  readonly files: ReadonlyArray<{
    readonly path: string
    readonly original: { readonly scheme: string; readonly path: string }
    readonly proposed: { readonly scheme: string; readonly path: string }
  }>
}

export const hasChangesCommand = (commands: ReadonlyArray<string>): boolean =>
  commands.includes("vscode.changes")

export const planDiffOpen = (
  hasMultiDiff: boolean,
  title: string,
  paths: ReadonlyArray<string>,
): DiffOpenPlan => ({
  mode: hasMultiDiff ? "multi" : "pairwise",
  title,
  files: paths.map((path) => ({
    path,
    original: virtualDocRef(ORIGINAL_SCHEME, path),
    proposed: virtualDocRef(PROPOSED_SCHEME, path),
  })),
})

export const diffTitle = (base: string, wholeFile: boolean): string =>
  wholeFile ? base : `${base} (region only)`
