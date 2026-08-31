import { expect, it } from "@effect/vitest"
import { chipFromSelection } from "@groks-beard/core"
import { ComposerState } from "../src/composer.ts"
import { EmptySessionTracker } from "../src/empty-sessions.ts"

it("accumulates selection chips into a TUI prompt", () => {
  const composer = new ComposerState()
  composer.addChip(chipFromSelection({
    absPath: "/repo/src/Foo.scala",
    workspaceRoot: "/repo",
    startLine: 10,
    endLine: 50,
  }))
  expect(composer.promptText("fix this", false)).toBe("@src/Foo.scala:10-50\n\nfix this")
})

it("copies a pending selection @ ref", () => {
  const composer = new ComposerState()
  const chip = chipFromSelection({
    absPath: "/repo/a.ts",
    workspaceRoot: "/repo",
    startLine: 1,
    endLine: 2,
  })
  composer.setPendingSelection(chip)
  expect(composer.pendingSelection?.path).toBe("a.ts")
})

it("deletes only empty sessions this process created", () => {
  const tracker = new EmptySessionTracker()
  tracker.markCreated("beard-1")
  tracker.markCreated("beard-2")
  tracker.markHasHistory("beard-2")
  expect(tracker.shouldDelete("beard-1")).toBe(true)
  expect(tracker.shouldDelete("beard-2")).toBe(false)
  expect(tracker.shouldDelete("tui-session")).toBe(false)
})
