import { expect, it } from "@effect/vitest"
import { isWorkspaceFilePath, pathIsInsideRoot } from "../src/workspace-path.ts"

it("treats workspace-relative paths as inside the workspace", () => {
  expect(isWorkspaceFilePath("src/Main.scala", ["/repo"])).toBe(true)
  expect(isWorkspaceFilePath("/repo/src/Main.scala", ["/repo"])).toBe(true)
})

it("rejects Grok session and temp files outside the workspace", () => {
  const root = "/Users/russ/projects/fun/saferis"
  expect(isWorkspaceFilePath(
    "/Users/russ/.grok/sessions/%2FUsers%2Fruss%2Fprojects%2Ffun%2Fsaferis/01abc/plan.md",
    [root],
  )).toBe(false)
  expect(isWorkspaceFilePath("/tmp/groks-beard-plan.md", [root])).toBe(false)
  expect(isWorkspaceFilePath("", [root])).toBe(false)
  expect(isWorkspaceFilePath("plan.md", [])).toBe(false)
})

it("matches path prefixes on a folder boundary", () => {
  expect(pathIsInsideRoot("/repo/src/a.ts", "/repo")).toBe(true)
  expect(pathIsInsideRoot("/repo-other/a.ts", "/repo")).toBe(false)
})
