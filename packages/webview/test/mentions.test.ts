import { expect, it } from "@effect/vitest"
import {
  mentionChoices,
  mentionPopoverOpen,
  mentionQueryFromDraft,
  moveMentionIndex,
} from "../src/mentions.ts"

it("reads a trailing @ token from the draft", () => {
  expect(mentionQueryFromDraft("@")).toBe("")
  expect(mentionQueryFromDraft("see @src/")).toBe("src/")
  expect(mentionQueryFromDraft("@Main.scala")).toBe("Main.scala")
  expect(mentionQueryFromDraft("hello")).toBeUndefined()
  expect(mentionQueryFromDraft("@done ")).toBeUndefined()
})

it("closes the mention popover when @ is gone or dismissed", () => {
  expect(mentionPopoverOpen("@fi", false)).toBe(true)
  expect(mentionPopoverOpen("@fi", true)).toBe(false)
  expect(mentionPopoverOpen("no mention", false)).toBe(false)
})

it("keeps mention choices only for the live @ query", () => {
  const files = [{ path: "a.ts", absPath: "/a.ts" }]
  expect(mentionChoices("@a", "a", files, false)).toEqual(files)
  expect(mentionChoices("done", "a", files, false)).toEqual([])
  expect(mentionChoices("@a", "ab", files, false)).toEqual([])
})

it("moves the mention highlight from the keyboard", () => {
  expect(moveMentionIndex(undefined, "ArrowUp", 3)).toBe(0)
  expect(moveMentionIndex(undefined, "ArrowDown", 3)).toBe(0)
  expect(moveMentionIndex(0, "ArrowDown", 3)).toBe(1)
  expect(moveMentionIndex(0, "ArrowUp", 3)).toBe(0)
  expect(moveMentionIndex(2, "ArrowDown", 3)).toBe(2)
  expect(moveMentionIndex(0, "ArrowUp", 0)).toBeUndefined()
})
