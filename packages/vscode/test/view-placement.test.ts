import { expect, it } from "@effect/vitest"
import {
  CHANGES_VIEW_ID,
  CHAT_VIEW_ID,
  isVsCodeHost,
  maybePlaceViews,
  SECONDARY_SIDE_BAR_DESTINATION,
  shouldMoveViewsOnActivate,
} from "../src/view-placement.ts"

it("gates Secondary Side Bar moves to VS Code hosts", () => {
  expect(isVsCodeHost("Visual Studio Code")).toBe(true)
  expect(isVsCodeHost("Cursor")).toBe(false)
  expect(shouldMoveViewsOnActivate({ appName: "Visual Studio Code", persisted: undefined })).toBe(
    true,
  )
  expect(shouldMoveViewsOnActivate({ appName: "Cursor", persisted: undefined })).toBe(false)
  expect(
    shouldMoveViewsOnActivate({ appName: "Visual Studio Code", persisted: "activitybar" }),
  ).toBe(false)
})

it("moves Chat and Grok Changes on first-run VS Code and persists", async () => {
  const moved: Array<{ viewIds: ReadonlyArray<string>; destinationId: string }> = []
  const persisted: Array<string> = []
  const placement = await maybePlaceViews({
    appName: "Visual Studio Code",
    persisted: undefined,
    persist: (value) => {
      persisted.push(value)
      return Promise.resolve()
    },
    moveViews: (viewIds, destinationId) => {
      moved.push({ viewIds, destinationId })
      return Promise.resolve()
    },
  })
  expect(placement).toBe("secondarySidebar")
  expect(moved).toEqual([{
    viewIds: [CHAT_VIEW_ID, CHANGES_VIEW_ID],
    destinationId: SECONDARY_SIDE_BAR_DESTINATION,
  }])
  expect(persisted).toEqual(["secondarySidebar"])
})

it("does not call moveViews on Cursor", async () => {
  let called = false
  const placement = await maybePlaceViews({
    appName: "Cursor",
    persisted: undefined,
    persist: () => Promise.resolve(),
    moveViews: () => {
      called = true
      return Promise.resolve()
    },
  })
  expect(called).toBe(false)
  expect(placement).toBe("activitybar")
})
