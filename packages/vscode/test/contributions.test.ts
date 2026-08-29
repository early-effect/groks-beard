import { expect, it } from "@effect/vitest"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const manifest = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../package.json"), "utf8"),
) as {
  contributes: {
    viewsContainers: { activitybar: Array<{ id: string }> }
    views: Record<string, Array<{ id: string; name: string }>>
    commands: Array<{ command: string }>
  }
}

it("contributes Chat and Grok Changes on the activity bar", () => {
  expect(manifest.contributes.viewsContainers.activitybar.map((row) => row.id)).toEqual([
    "groks-beard",
  ])
  expect(manifest.contributes.views["groks-beard"]?.map((row) => row.id)).toEqual([
    "groksBeard.chat",
    "groksBeard.changes",
  ])
  expect(manifest.contributes.views.secondarySidebar).toBeUndefined()
  expect(manifest.contributes.commands.some((row) => row.command === "groksBeard.open")).toBe(true)
})
