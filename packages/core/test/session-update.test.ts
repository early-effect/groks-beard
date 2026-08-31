import { expect, it } from "@effect/vitest"
import {
  CurrentModeUpdate,
  decodeSessionUpdate,
  modeIdFromUpdate,
  occupancyFromUnknown,
  textFromContent,
  toolPayloadText,
  UnknownUpdate,
  UsageUpdate,
} from "../src/session-update.ts"

it("decodes Grok current_mode_update with currentModeId", () => {
  const update = decodeSessionUpdate({
    sessionUpdate: "current_mode_update",
    currentModeId: "plan",
  })
  expect(update).toBeInstanceOf(CurrentModeUpdate)
  expect(modeIdFromUpdate(update as CurrentModeUpdate)).toBe("plan")
})

it("does not fail the fiber on an unknown sessionUpdate", () => {
  const update = decodeSessionUpdate({
    sessionUpdate: "brand_new_event",
    extra: true,
  })
  expect(update).toBeInstanceOf(UnknownUpdate)
  expect(update.sessionUpdate).toBe("brand_new_event")
})

it("wraps non-objects as UnknownUpdate", () => {
  const update = decodeSessionUpdate(null)
  expect(update.sessionUpdate).toBe("unknown")
})

it("reads thought text from nested content blocks", () => {
  expect(textFromContent({ type: "text", text: "Considering.\n" })).toBe("Considering.\n")
  expect(textFromContent({ content: { text: "Then I'll answer.\n" } })).toBe("Then I'll answer.\n")
  expect(textFromContent([{ text: "a" }, { text: "b" }])).toBe("ab")
})

it("decodes usage_update occupancy", () => {
  const update = decodeSessionUpdate({
    sessionUpdate: "usage_update",
    used: 1200,
    size: 128000,
  })
  expect(update).toBeInstanceOf(UsageUpdate)
})

it("formats tool payloads as text or pretty JSON", () => {
  expect(toolPayloadText({ query: "metals", limit: 20 })).toContain('"query": "metals"')
  expect(toolPayloadText([{ type: "content", content: { type: "text", text: "out" } }])).toBe("out")
  expect(toolPayloadText({})).toBe("")
})

it("reads occupancy from used/size or nested usage", () => {
  expect(occupancyFromUnknown({ used: 12, size: 100 })).toEqual({ used: 12, size: 100 })
  expect(occupancyFromUnknown({ usage: { inputTokens: 20, context_window: 500 } })).toEqual({
    used: 20,
    size: 500,
  })
  expect(occupancyFromUnknown({ used: 10 }, 256)).toEqual({ used: 10, size: 256 })
  expect(occupancyFromUnknown({
    _meta: { totalTokens: 29183 },
  }, 500000)).toEqual({ used: 29183, size: 500000 })
  expect(occupancyFromUnknown({
    update: {
      sessionUpdate: "turn_completed",
      usage: { inputTokens: 28559, totalTokens: 28776 },
    },
  }, 500000)).toEqual({ used: 28559, size: 500000 })
})
