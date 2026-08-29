import { expect, it } from "@effect/vitest"
import { hasChangesCommand, planDiffOpen } from "../src/diff-open.ts"
import { ORIGINAL_SCHEME, PROPOSED_SCHEME } from "../src/virtual-docs.ts"

it("uses vscode.changes when the command exists", () => {
  expect(hasChangesCommand(["vscode.diff", "vscode.changes"])).toBe(true)
  const plan = planDiffOpen(true, "Review edits", ["/tmp/a.ts"])
  expect(plan.mode).toBe("multi")
  expect(plan.files[0]?.original.scheme).toBe(ORIGINAL_SCHEME)
  expect(plan.files[0]?.proposed.scheme).toBe(PROPOSED_SCHEME)
})

it("falls back to pairwise vscode.diff when multi-diff is missing", () => {
  expect(hasChangesCommand(["vscode.diff"])).toBe(false)
  const plan = planDiffOpen(false, "Review edits", ["/tmp/a.ts", "/tmp/b.ts"])
  expect(plan.mode).toBe("pairwise")
  expect(plan.files).toHaveLength(2)
})
