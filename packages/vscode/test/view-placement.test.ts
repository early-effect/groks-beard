import { expect, it } from "@effect/vitest"
import {
  CHANGES_VIEW_ID,
  CHANGES_VIEW_ID_SECONDARY,
  changesViewIdForHost,
  CHAT_VIEW_ID,
  CHAT_VIEW_ID_SECONDARY,
  chatViewIdForHost,
  isVsCodeHost,
  placementForHost,
} from "../src/view-placement.ts"

it("gates Secondary Side Bar to VS Code hosts", () => {
  expect(isVsCodeHost("Visual Studio Code")).toBe(true)
  expect(isVsCodeHost("VS Code")).toBe(true)
  expect(isVsCodeHost("Cursor")).toBe(false)
  expect(placementForHost("Visual Studio Code")).toBe("secondarySidebar")
  expect(placementForHost("Cursor")).toBe("activitybar")
})

it("focuses the secondary chat tab on VS Code and the activity-bar view on Cursor", () => {
  expect(chatViewIdForHost("Visual Studio Code")).toBe(CHAT_VIEW_ID_SECONDARY)
  expect(chatViewIdForHost("Cursor")).toBe(CHAT_VIEW_ID)
  expect(changesViewIdForHost("Visual Studio Code")).toBe(CHANGES_VIEW_ID_SECONDARY)
  expect(changesViewIdForHost("Cursor")).toBe(CHANGES_VIEW_ID)
})
