import type { ToolLocation } from "@groks-beard/core"
import { ORIGINAL_SCHEME, PROPOSED_SCHEME } from "./virtual-docs.js"

export type FollowAlongPlan = {
  readonly preserveFocus: boolean
  readonly reveals: ReadonlyArray<{
    readonly path: string
    readonly line?: number
  }>
}

export const isVirtualDiffScheme = (scheme: string | undefined): boolean =>
  scheme === ORIGINAL_SCHEME || scheme === PROPOSED_SCHEME

export const planFollowAlong = (
  locations: ReadonlyArray<ToolLocation>,
  active: { readonly scheme?: string; readonly inDiffEditor?: boolean }
): FollowAlongPlan => ({
  preserveFocus: isVirtualDiffScheme(active.scheme) || active.inDiffEditor === true,
  reveals: locations.map((location) =>
    location.line !== undefined ? { path: location.path, line: location.line } : { path: location.path }
  )
})
