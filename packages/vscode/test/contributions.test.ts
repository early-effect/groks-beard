import { expect, it } from "@effect/vitest"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const manifest = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../package.json"), "utf8"),
) as {
  contributes: {
    viewsContainers: {
      activitybar: Array<{ id: string; when?: string }>
      secondarySidebar: Array<{ id: string; when?: string }>
    }
    views: Record<string, Array<{ id: string; name: string; when?: string }>>
    commands: Array<{ command: string; title?: string }>
    keybindings: Array<{ command: string; key: string; when?: string }>
    "markdown.previewScripts"?: Array<string>
    configuration: {
      properties: Record<string, { default?: unknown; enum?: Array<string> }>
    }
  }
}

it("contributes Grok's Beard as a secondary-sidebar tab with an activity-bar fallback", () => {
  expect(manifest.contributes.viewsContainers.activitybar.map((row) => row.id)).toEqual([
    "groks-beard",
  ])
  expect(manifest.contributes.viewsContainers.activitybar[0]?.when).toBe(
    "groksBeard.useActivityBar",
  )
  expect(manifest.contributes.viewsContainers.secondarySidebar.map((row) => row.id)).toEqual([
    "groks-beard-secondary",
  ])
  expect(manifest.contributes.viewsContainers.secondarySidebar[0]?.when).toBe(
    "!groksBeard.useActivityBar",
  )
  expect(manifest.contributes.views["groks-beard"]?.map((row) => row.id)).toEqual([
    "groksBeard.chat",
    "groksBeard.changes",
  ])
  expect(manifest.contributes.views["groks-beard"]?.[1]?.when).toBe(
    "groksBeard.useActivityBar && groksBeard.changesPane",
  )
  expect(manifest.contributes.views["groks-beard-secondary"]?.map((row) => row.id)).toEqual([
    "groksBeard.chatSecondary",
    "groksBeard.changesSecondary",
  ])
  expect(manifest.contributes.views["groks-beard-secondary"]?.[1]?.when).toBe(
    "!groksBeard.useActivityBar && groksBeard.changesPane",
  )
  expect(manifest.contributes.commands.some((row) => row.command === "groksBeard.open")).toBe(true)
  expect(
    manifest.contributes.commands.some((row) =>
      row.command === "groksBeard.cancel" && row.title === "Grok's Beard: Stop"
    ),
  ).toBe(true)
  expect(
    manifest.contributes.keybindings.some((row) =>
      row.command === "groksBeard.cancel" && row.key === "escape"
    ),
  ).toBe(true)
  expect(
    manifest.contributes.keybindings.some((row) =>
      row.command === "groksBeard.addSelection" && row.key === "ctrl+shift+;"
    ),
  ).toBe(true)
  expect(manifest.contributes["markdown.previewScripts"]).toEqual([
    "./media/markdown-preview-selection.js",
  ])
  expect(manifest.contributes.commands.some((row) => row.command === "groksBeard.openSettings"))
    .toBe(true)
  expect(
    manifest.contributes.commands.some((row) => row.command === "groksBeard.openChangesReview"),
  )
    .toBe(true)
  expect(manifest.contributes.commands.some((row) => row.command === "groksBeard.commitChanges"))
    .toBe(true)
  expect(manifest.contributes.configuration.properties["groksBeard.changesPresentation"]?.default)
    .toBe("toast")
  expect(manifest.contributes.configuration.properties["groksBeard.changesPresentation"]?.enum)
    .toEqual(["toast", "pane"])
})
