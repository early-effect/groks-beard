import { expect, it } from "@effect/vitest"
import {
  linesFromPreviewDataLine,
  markdownPreviewPathFromTabInput,
  parsePreviewSelection,
  previewResourcePath,
  selectionToAdd,
} from "../src/add-selection.ts"

it("reads any markdown preview resource, not only plan.md", () => {
  expect(markdownPreviewPathFromTabInput({
    viewType: "vscode.markdown.preview.editor",
    uri: { fsPath: "/repo/README.md" },
  })).toBe("/repo/README.md")
  expect(markdownPreviewPathFromTabInput({
    viewType: "markdown.preview",
    uri: { fsPath: "/tmp/notes.md" },
  })).toBe("/tmp/notes.md")
  expect(markdownPreviewPathFromTabInput({
    viewType: "workbench.editor.webview",
  })).toBeUndefined()
})

it("falls back to a visible editor matching the preview tab label", () => {
  expect(previewResourcePath({
    tabInput: { viewType: "markdown.preview" },
    tabLabel: "Preview README.md",
    editors: [
      { fsPath: "/repo/src/Main.scala" },
      { fsPath: "/repo/README.md" },
    ],
  })).toBe("/repo/README.md")
})

it("maps markdown preview data-line values to 1-based source lines", () => {
  expect(linesFromPreviewDataLine(2, 9)).toEqual({ startLine: 3, endLine: 10 })
  expect(linesFromPreviewDataLine(4, undefined)).toEqual({ startLine: 5, endLine: 5 })
  expect(linesFromPreviewDataLine(undefined, undefined)).toBeUndefined()
})

it("parses a preview selection payload from a command URI argument", () => {
  expect(parsePreviewSelection({
    excerpt: "  Ship the chip.  ",
    startLine: 12,
    endLine: 40,
  })).toEqual({
    excerpt: "Ship the chip.",
    startLine: 12,
    endLine: 40,
  })
  expect(parsePreviewSelection([{ text: "quoted" }])).toEqual({ excerpt: "quoted" })
  expect(parsePreviewSelection({ excerpt: "   " })).toBeUndefined()
})

it("adds a preview highlight without using a leftover editor of another file", () => {
  const leftover = {
    fsPath: "/repo/ZipxVersions.scala",
    empty: false,
    startLine: 8,
    startCol: 1,
    endLine: 20,
    endCol: 4,
    excerpt: "wrong file",
    languageId: "scala",
  }
  expect(selectionToAdd({
    previewPath: "/repo/README.md",
    payload: { excerpt: "Install zipx.", startLine: 4, endLine: 6 },
    activeEditor: leftover,
    editors: [leftover],
  })).toEqual({
    absPath: "/repo/README.md",
    excerpt: "Install zipx.",
    startLine: 4,
    endLine: 6,
    languageId: "markdown",
  })
})

it("uses the visible editor of the previewed file when the preview sent no payload", () => {
  const readme = {
    fsPath: "/repo/README.md",
    empty: false,
    startLine: 2,
    startCol: 1,
    endLine: 5,
    endCol: 8,
    excerpt: "hello",
    languageId: "markdown",
  }
  expect(selectionToAdd({
    previewPath: "/repo/README.md",
    activeEditor: {
      fsPath: "/repo/ZipxVersions.scala",
      empty: false,
      startLine: 1,
      startCol: 1,
      endLine: 1,
      endCol: 4,
      excerpt: "nope",
    },
    editors: [readme],
  })).toEqual({
    absPath: "/repo/README.md",
    excerpt: "hello",
    startLine: 2,
    endLine: 5,
    languageId: "markdown",
  })
})

it("uses the active editor when no markdown preview is focused", () => {
  const editor = {
    fsPath: "/repo/src/Main.scala",
    empty: false,
    startLine: 10,
    startCol: 1,
    endLine: 12,
    endCol: 4,
    excerpt: "def main",
    languageId: "scala",
  }
  expect(selectionToAdd({
    activeEditor: editor,
    editors: [editor],
  })).toEqual({
    absPath: "/repo/src/Main.scala",
    excerpt: "def main",
    startLine: 10,
    endLine: 12,
    languageId: "scala",
  })
})
