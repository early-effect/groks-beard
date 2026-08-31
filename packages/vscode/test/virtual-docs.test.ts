import { expect, it } from "@effect/vitest"
import {
  BeardDocStore,
  filePathFromEditorUri,
  ORIGINAL_SCHEME,
  PROPOSED_SCHEME,
} from "../src/virtual-docs.ts"

it("serves original and proposed bodies by absolute path", () => {
  const docs = new BeardDocStore()
  docs.setPair("/tmp/a.ts", "old", "new")
  expect(docs.get(ORIGINAL_SCHEME, "/tmp/a.ts")).toBe("old")
  expect(docs.get(PROPOSED_SCHEME, "/tmp/a.ts")).toBe("new")
  expect(docs.get(ORIGINAL_SCHEME, "/missing.ts")).toBe("")
})

it("reads the real path from a beard virtual diff URI", () => {
  expect(filePathFromEditorUri({
    scheme: PROPOSED_SCHEME,
    path: "/tmp/a.ts",
    fsPath: "/tmp/a.ts",
  })).toBe("/tmp/a.ts")
  expect(filePathFromEditorUri({
    scheme: ORIGINAL_SCHEME,
    path: "/C:/Users/a.ts",
    fsPath: "C:\\Users\\a.ts",
  })).toBe("C:/Users/a.ts")
  expect(filePathFromEditorUri({
    scheme: "file",
    path: "/tmp/a.ts",
    fsPath: "/tmp/a.ts",
  })).toBe("/tmp/a.ts")
})

it("normalizes backslashes so Windows paths still hit", () => {
  const docs = new BeardDocStore()
  docs.set(ORIGINAL_SCHEME, "C:\\Users\\a.ts", "body")
  expect(docs.get(ORIGINAL_SCHEME, "/C:/Users/a.ts")).toBe("body")
})
