import { expect, it } from "@effect/vitest"
import {
  clientCapabilities,
  fakeSpawnCapabilityPolicy,
  liveSpawnCapabilityPolicy,
} from "../src/capabilities.ts"
import { parseGrokVersion } from "../src/version.ts"

it("withholds fs.readTextFile for live-verified Grok >= 1.0.4", () => {
  const caps = clientCapabilities({
    version: parseGrokVersion("grok 1.0.13")!,
    versionVerified: true,
    terminalHandlersReady: false,
  })
  expect(caps.fs).toBeUndefined()
  expect(caps.terminal).toBeUndefined()
})

it("advertises fs.readTextFile for 1.0.3", () => {
  const caps = clientCapabilities({
    version: parseGrokVersion("grok 1.0.3")!,
    versionVerified: true,
    terminalHandlersReady: false,
  })
  expect(caps.fs?.readTextFile).toBe(true)
})

it("advertises fs.readTextFile when the version is unverified", () => {
  const caps = clientCapabilities({
    version: parseGrokVersion("grok 1.0.13")!,
    versionVerified: false,
    terminalHandlersReady: false,
  })
  expect(caps.fs?.readTextFile).toBe(true)
})

it("live spawn advertises terminal once handlers are ready", () => {
  const live = liveSpawnCapabilityPolicy({
    version: parseGrokVersion("grok 1.0.13")!,
    verified: true,
  })
  expect(live.terminalHandlersReady).toBe(true)
  expect(clientCapabilities(live).terminal).toBe(true)
  expect(clientCapabilities(live).fs).toBeUndefined()
  expect(fakeSpawnCapabilityPolicy().terminalHandlersReady).toBe(false)
})

it("omits terminal until handlers are ready", () => {
  const before = clientCapabilities({
    version: parseGrokVersion("grok 1.0.13")!,
    versionVerified: true,
    terminalHandlersReady: false,
  })
  const after = clientCapabilities({
    version: parseGrokVersion("grok 1.0.13")!,
    versionVerified: true,
    terminalHandlersReady: true,
  })
  expect(before.terminal).toBeUndefined()
  expect(after.terminal).toBe(true)
})
