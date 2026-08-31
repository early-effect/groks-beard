import type { ClientCapabilities } from "@agentclientprotocol/sdk"
import { type GrokVersion, isAtLeast } from "./version.js"

export const FS_READ_FLOOR = { major: 1, minor: 0, patch: 4 } as const

export type CapabilityPolicy = {
  readonly version: GrokVersion | undefined
  readonly versionVerified: boolean
  readonly terminalHandlersReady: boolean
}

export const clientCapabilities = (policy: CapabilityPolicy): ClientCapabilities => {
  const caps: ClientCapabilities = {}
  const advertiseFsRead = !(
    policy.versionVerified
    && policy.version !== undefined
    && isAtLeast(policy.version, FS_READ_FLOOR)
  )
  if (advertiseFsRead) {
    caps.fs = { readTextFile: true }
  }
  if (policy.terminalHandlersReady) {
    caps.terminal = true
  }
  caps.session = { configOptions: {} }
  return caps
}

export const initializeParams = (
  policy: CapabilityPolicy,
  extensionVersion: string,
): {
  protocolVersion: 1
  clientCapabilities: ClientCapabilities
  clientInfo: { name: string; title: string; version: string }
} => ({
  protocolVersion: 1,
  clientCapabilities: clientCapabilities(policy),
  clientInfo: {
    name: "groks-beard",
    title: "Grok's Beard",
    version: extensionVersion,
  },
})

export const liveSpawnCapabilityPolicy = (
  resolved: { readonly version: GrokVersion | undefined; readonly verified: boolean },
): CapabilityPolicy => ({
  version: resolved.version,
  versionVerified: resolved.verified,
  terminalHandlersReady: true,
})

export const fakeSpawnCapabilityPolicy = (): CapabilityPolicy => ({
  version: undefined,
  versionVerified: false,
  terminalHandlersReady: false,
})
