import { expect, it } from "@effect/vitest"
import { ChangeSetRecord, FileChangeRecord } from "@groks-beard/core"
import { changesPaneVisible, changesPresentationFrom } from "../src/changes-presentation.ts"
import { displayChangePath, reviewTurnsFromSets } from "../src/changes-review-model.ts"

it("defaults unknown presentation values to toast", () => {
  expect(changesPresentationFrom(undefined)).toBe("toast")
  expect(changesPresentationFrom("toast")).toBe("toast")
  expect(changesPresentationFrom("pane")).toBe("pane")
  expect(changesPaneVisible("toast")).toBe(false)
  expect(changesPaneVisible("pane")).toBe(true)
})

it("builds review turns with workspace-relative paths", () => {
  const set = new ChangeSetRecord({
    sessionId: "s",
    turnId: "t",
    title: "Edit the file",
    createdAt: 1,
    files: [
      new FileChangeRecord({
        path: "/proj/src/a.ts",
        kind: "modify",
        additions: 3,
        deletions: 1,
        wholeFile: true,
        toolCallId: "c1",
        snapshotStored: false,
        snapshotBytes: 0,
      }),
    ],
  })
  expect(displayChangePath("/proj/src/a.ts", "/proj")).toBe("src/a.ts")
  const turns = reviewTurnsFromSets([set], "/proj")
  expect(turns[0]?.title).toBe("Edit the file")
  expect(turns[0]?.files[0]?.name).toBe("src/a.ts")
  expect(turns[0]?.additions).toBe(3)
  expect(turns[0]?.canUndo).toBe(true)
})
