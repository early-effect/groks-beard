import { expect, it } from "@effect/vitest"
import { grokModelsCachePath, readGrokModelCatalog } from "../src/grok-models.ts"

it("reads models_cache.json under GROK_HOME", () => {
  const env = { GROK_HOME: "/tmp/beard-grok-home" }
  expect(grokModelsCachePath(env)).toBe("/tmp/beard-grok-home/models_cache.json")
  expect(readGrokModelCatalog(env, () => {
    throw new Error("missing")
  })).toBeUndefined()
  expect(readGrokModelCatalog(env, () => '{"models":{"grok-4.6":{"info":{"id":"grok-4.6"}}}}'))
    .toEqual({
      models: { "grok-4.6": { info: { id: "grok-4.6" } } },
    })
})
