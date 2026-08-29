import { expect, it } from "@effect/vitest"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { parseGrokVersion, readGrokVersionBanner, resolveGrokVersion } from "../src/version.ts"

it("parses the 1.0.13 banner", () => {
  const version = parseGrokVersion("grok 1.0.13 (5e9a58528b76) [stable]\n")
  expect(version?.major).toBe(1)
  expect(version?.minor).toBe(0)
  expect(version?.patch).toBe(13)
  expect(version?.git).toBe("5e9a58528b76")
  expect(version?.channel).toBe("stable")
})

it("parses 1.0.3 and 1.0.4", () => {
  expect(parseGrokVersion("grok 1.0.3")?.patch).toBe(3)
  expect(parseGrokVersion("grok 1.0.4 (abc)")?.patch).toBe(4)
})

it("returns undefined for unreadable and empty banners", () => {
  expect(parseGrokVersion("not a version")).toBeUndefined()
  expect(parseGrokVersion("")).toBeUndefined()
  expect(parseGrokVersion("   \n")).toBeUndefined()
})

it("uses a matching cache only when live stdout is unparseable", () => {
  const cached = parseGrokVersion("grok 1.0.13")!
  const resolved = resolveGrokVersion("", {
    version: cached,
    mtimeMs: 1,
    size: 10,
  }, { mtimeMs: 1, size: 10 })
  expect(resolved.verified).toBe(false)
  expect(resolved.version?.patch).toBe(13)
})

it("reads a version banner from the fake grok fixture", async () => {
  const fixture = join(dirname(fileURLToPath(import.meta.url)), "fixtures/fake-grok.mjs")
  const banner = await readGrokVersionBanner(process.execPath, { args: [fixture] })
  expect(parseGrokVersion(banner)?.patch).toBe(13)
})

it("prefers a live parseable banner over cache", () => {
  const cached = parseGrokVersion("grok 1.0.3")!
  const resolved = resolveGrokVersion("grok 1.0.13", {
    version: cached,
    mtimeMs: 1,
    size: 10,
  }, { mtimeMs: 1, size: 10 })
  expect(resolved.verified).toBe(true)
  expect(resolved.version?.patch).toBe(13)
})
