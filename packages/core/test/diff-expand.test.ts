import { expect, it } from "@effect/vitest"
import * as fc from "effect/testing/FastCheck"
import { expandDiffToWholeFile, MAX_DIFF_EXPAND_BYTES } from "../src/diff-expand.ts"

it("substitutes a region in the middle of a file at permission time", () => {
  const disk = "aaa\nold-token\nccc\n"
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "old-token",
    newRegion: "new-token",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(true)
  expect(sides.oldText).toBe(disk)
  expect(sides.newText).toBe("aaa\nnew-token\nccc\n")
  expect(sides.firstChangedLine).toBe(1)
})

it("treats empty oldRegion plus non-empty disk as a whole-file write", () => {
  const disk = "keep me"
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "",
    newRegion: "replaced",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(true)
  expect(sides.oldText).toBe(disk)
  expect(sides.newText).toBe("replaced")
})

it("falls back to region-only when the file is missing", () => {
  const sides = expandDiffToWholeFile({
    diskText: undefined,
    oldRegion: "old",
    newRegion: "new",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(false)
  expect(sides.oldText).toBe("old")
  expect(sides.newText).toBe("new")
})

it("treats a missing disk plus empty oldRegion as a new file", () => {
  const sides = expandDiffToWholeFile({
    diskText: undefined,
    oldRegion: "",
    newRegion: "hello",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(true)
  expect(sides.oldText).toBe("")
  expect(sides.newText).toBe("hello")
})

it("falls back to region-only when the file is oversize", () => {
  const disk = "x".repeat(MAX_DIFF_EXPAND_BYTES + 1)
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "x",
    newRegion: "y",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(false)
})

it("does not manufacture a CRLF diff when the region arrived with LF", () => {
  const disk = "aaa\r\nold\r\nccc\r\n"
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "old",
    newRegion: "new",
    diskIsBefore: true,
  })
  expect(sides.wholeFile).toBe(true)
  expect(sides.newText).toContain("\r\n")
  expect(sides.newText).toBe("aaa\r\nnew\r\nccc\r\n")
})

it("replaceAll substitutes every match", () => {
  const disk = "foo bar foo"
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "foo",
    newRegion: "baz",
    diskIsBefore: true,
    replaceAll: true,
  })
  expect(sides.newText).toBe("baz bar baz")
})

it("recovers the original from a post-write disk", () => {
  const disk = "aaa\nnew-token\nccc\n"
  const sides = expandDiffToWholeFile({
    diskText: disk,
    oldRegion: "old-token",
    newRegion: "new-token",
    diskIsBefore: false,
  })
  expect(sides.oldText).toBe("aaa\nold-token\nccc\n")
  expect(sides.newText).toBe(disk)
})

it("permission-time expansion is invertible via the post-write path", () => {
  fc.assert(
    fc.property(
      fc.string().filter((s) => !s.includes("OLD") && !s.includes("NEW")),
      fc.string().filter((s) => !s.includes("OLD") && !s.includes("NEW")),
      (prefix, suffix) => {
        const disk = `${prefix}OLD${suffix}`
        const before = expandDiffToWholeFile({
          diskText: disk,
          oldRegion: "OLD",
          newRegion: "NEW",
          diskIsBefore: true,
        })
        const after = expandDiffToWholeFile({
          diskText: before.newText,
          oldRegion: "OLD",
          newRegion: "NEW",
          diskIsBefore: false,
        })
        return after.oldText === disk && after.newText === before.newText
      },
    ),
  )
})
