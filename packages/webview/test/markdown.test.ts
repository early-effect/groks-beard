import { expect, it } from "@effect/vitest"
import { ALLOWED_URI_REGEXP, renderMarkdownDirty } from "../src/markdown.ts"

it("allows only https, vscode-file, and vscode-webview URIs", () => {
  expect(ALLOWED_URI_REGEXP.test("https://example.com")).toBe(true)
  expect(ALLOWED_URI_REGEXP.test("http://localhost")).toBe(true)
  expect(ALLOWED_URI_REGEXP.test("vscode-file://file/tmp")).toBe(true)
  expect(ALLOWED_URI_REGEXP.test("vscode-webview://id/media")).toBe(true)
  expect(ALLOWED_URI_REGEXP.test("javascript:alert(1)")).toBe(false)
  expect(ALLOWED_URI_REGEXP.test("data:text/html;base64,aaa")).toBe(false)
})

it("renders markdown to html without raw html passthrough", () => {
  const html = renderMarkdownDirty("hello **grok**\n\n<script>alert(1)</script>")
  expect(html).toContain("<strong>grok</strong>")
  expect(html).not.toContain("<script>")
})
