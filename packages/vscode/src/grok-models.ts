import { grokHome } from "@groks-beard/core"
import { readFileSync } from "node:fs"

export const grokModelsCachePath = (env: Record<string, string | undefined>): string =>
  `${grokHome(env).replace(/[\\/]+$/, "")}/models_cache.json`

export const readGrokModelCatalog = (
  env: Record<string, string | undefined> = process.env,
  readText: (path: string) => string = (path) => readFileSync(path, "utf8"),
): unknown => {
  try {
    return JSON.parse(readText(grokModelsCachePath(env)))
  } catch {
    return undefined
  }
}
