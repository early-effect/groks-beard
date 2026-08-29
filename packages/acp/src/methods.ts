import { Schema } from "effect"

export const GROK_EXT_PREFIX = "_x.ai/"
export const GROK_EXT_BARE_PREFIX = "x.ai/"

export const grokMethodFromWire = (method: string): string =>
  method.startsWith(GROK_EXT_BARE_PREFIX) && !method.startsWith(GROK_EXT_PREFIX)
    ? `_${method}`
    : method

export const isGrokExtensionMethod = (method: string): boolean =>
  grokMethodFromWire(method).startsWith(GROK_EXT_PREFIX)

export const XaiToolMeta = Schema.Struct({
  version: Schema.optionalKey(Schema.Unknown),
  name: Schema.optionalKey(Schema.String),
  kind: Schema.optionalKey(Schema.String),
  namespace: Schema.optionalKey(Schema.String),
  label: Schema.optionalKey(Schema.String),
  read_only: Schema.optionalKey(Schema.Boolean)
})

export const xaiToolFromMeta = (meta: unknown): typeof XaiToolMeta.Type | undefined => {
  if (typeof meta !== "object" || meta === null) return undefined
  const tool = (meta as Record<string, unknown>)["x.ai/tool"]
  const decoded = Schema.decodeUnknownExit(XaiToolMeta)(tool)
  return decoded._tag === "Success" ? decoded.value : undefined
}

export const COMMIT_BEFORE_CONTINUE = new Set(["session/set_mode"])

export const JSON_RPC_METHOD_NOT_FOUND = -32601
