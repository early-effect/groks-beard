import { expect, it } from "@effect/vitest"
import { formatAtRef, PromptChip, truncateToByteCap, utf8ByteLength } from "../src/prompt.ts"

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
