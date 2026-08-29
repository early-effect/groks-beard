import { expect, it } from "@effect/vitest"
import {
  buildPromptText,
  chipFromFile,
  chipFromSelection,
  chipsForSend,
  formatAtRef,
  PromptChip,
  truncateToByteCap,
  utf8ByteLength
} from "../src/prompt.ts"

it("formats a file chip as @path", () => {
  const chip = new PromptChip({
    path: "src/Foo.scala",
    absPath: "/abs/src/Foo.scala",
    source: "file"
  })
  expect(formatAtRef(chip)).toBe("@src/Foo.scala")
})

it("formats a selection as @path:start-end", () => {
  const chip = new PromptChip({
    path: "src/Foo.scala",
    absPath: "/abs/src/Foo.scala",
    startLine: 10,
    endLine: 50,
    source: "selection"
  })
  expect(formatAtRef(chip)).toBe("@src/Foo.scala:10-50")
})

it("truncates embeddings to the byte cap", () => {
  const text = "a".repeat(40 * 1024)
  const truncated = truncateToByteCap(text)
  expect(utf8ByteLength(truncated)).toBeLessThanOrEqual(32 * 1024)
})

it("builds a TUI-shaped prompt from selection chips", () => {
  const selection = chipFromSelection({
    absPath: "/repo/src/Foo.scala",
    workspaceRoot: "/repo",
    startLine: 10,
    endLine: 50
  })
  expect(formatAtRef(selection)).toBe("@src/Foo.scala:10-50")
  expect(buildPromptText("explain", [selection])).toBe("@src/Foo.scala:10-50\n\nexplain")
  const active = chipFromFile({
    absPath: "/repo/src/Bar.scala",
    workspaceRoot: "/repo",
    source: "active"
  })
  expect(chipsForSend({
    chips: [],
    activeFile: active,
    includeActiveFileByDefault: true
  })).toEqual([active])
  expect(chipsForSend({
    chips: [selection],
    activeFile: active,
    includeActiveFileByDefault: true
  })).toEqual([selection])
})
