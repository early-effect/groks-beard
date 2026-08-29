import { expect, it } from "@effect/vitest"
import {
  CurrentModeUpdate,
  decodeSessionUpdate,
  modeIdFromUpdate,
  textFromContent,
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
