import DOMPurify from "dompurify"
import MarkdownIt from "markdown-it"

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

export const ALLOWED_URI_REGEXP = /^(?:https?|vscode-file|vscode-webview):/i

const ALLOWED_TAGS = [
  "p",
  "br",
  "strong",
  "em",
  "code",
  "pre",
  "a",
  "ul",
  "ol",
  "li",
  "blockquote",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "hr",
]

export const renderMarkdownDirty = (text: string): string => md.render(text)

export const sanitizeHtml = (html: string): string => {
  if (typeof globalThis.window === "undefined") return html
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_URI_REGEXP,
    ALLOWED_ATTR: ["href", "title"],
  })
}

export const renderMarkdown = (text: string): string => sanitizeHtml(renderMarkdownDirty(text))
