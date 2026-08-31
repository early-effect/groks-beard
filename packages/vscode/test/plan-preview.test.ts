import { expect, it } from "@effect/vitest"
import { encodeCwd } from "@groks-beard/core"
import { join } from "node:path"
import {
  FALLBACK_PLAN_FILE,
  grokSessionPlanPath,
  isGrokPlanPath,
  planPathFromTabInput,
  resolveChatEditorFile,
  resolvePlanFile,
  tabLabelLooksLikePlan,
} from "../src/plan-preview.ts"

it("points at the Grok session plan.md when that file exists", () => {
  const home = "/Users/russ/.grok"
  const cwd = "/Users/russ/projects/fun/saferis"
  const sessionId = "01abc"
  const session = grokSessionPlanPath(home, cwd, sessionId)
  expect(session).toBe(join(home, "sessions", encodeCwd(cwd), sessionId, "plan.md"))
  expect(resolvePlanFile({
    home,
    cwd,
    sessionId,
    tmpDir: "/tmp",
    exists: (path) => path === session,
  })).toEqual({ path: session, fromSession: true })
})

it("falls back to a temp plan file when the session has none", () => {
  expect(resolvePlanFile({
    home: "/Users/russ/.grok",
    cwd: "/repo",
    sessionId: "s1",
    tmpDir: "/tmp",
    exists: () => false,
  })).toEqual({
    path: join("/tmp", FALLBACK_PLAN_FILE),
    fromSession: false,
  })
})

it("recognizes Grok session plan.md and the fallback preview file", () => {
  expect(isGrokPlanPath(
    "/Users/russ/.grok/sessions/%2FUsers%2Fruss%2Fprojects%2Ffun%2Fsaferis/01abc/plan.md",
  )).toBe(true)
  expect(isGrokPlanPath("/tmp/groks-beard-plan.md")).toBe(true)
  expect(isGrokPlanPath("/Users/russ/projects/fun/saferis/README.md")).toBe(false)
  expect(tabLabelLooksLikePlan("plan.md")).toBe(true)
  expect(tabLabelLooksLikePlan("Preview plan.md")).toBe(true)
  expect(tabLabelLooksLikePlan("README.md")).toBe(false)
})

it("treats a markdown preview tab of the plan as the chat's current file", () => {
  const sessionPlan =
    "/Users/russ/.grok/sessions/%2FUsers%2Fruss%2Fprojects%2Ffun%2Fsaferis/01abc/plan.md"
  expect(planPathFromTabInput({
    viewType: "vscode.markdown.preview.editor",
    uri: { fsPath: sessionPlan },
  })).toBe(sessionPlan)
  expect(planPathFromTabInput(
    { viewType: "markdown.preview" },
    { label: "plan.md", knownPlanPath: sessionPlan },
  )).toBe(sessionPlan)
  expect(planPathFromTabInput(
    { viewType: "markdown.preview" },
    { label: "README.md", knownPlanPath: sessionPlan },
  )).toBeUndefined()
  expect(planPathFromTabInput({
    viewType: "vscode.markdown.preview.editor",
    uri: { fsPath: "/repo/README.md" },
  })).toBeUndefined()
  expect(resolveChatEditorFile({
    activeTab: { label: "plan.md", input: { viewType: "markdown.preview" } },
    editor: { fsPath: "/repo/ZipxVersions.scala", scheme: "file" },
    knownPlanPath: sessionPlan,
  })).toEqual({ absPath: sessionPlan, fromPlanPreview: true })
  expect(resolveChatEditorFile({
    editor: { fsPath: "/repo/ZipxVersions.scala", scheme: "file" },
  })).toEqual({ absPath: "/repo/ZipxVersions.scala", fromPlanPreview: false })
})
