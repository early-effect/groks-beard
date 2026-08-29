import { expect, it } from "@effect/vitest"
import {
  CurrentModeUpdate,
  decodeSessionUpdate,
  modeIdFromUpdate,
  UnknownUpdate
} from "../src/session-update.ts"

it("decodes Grok current_mode_update with currentModeId", () => {
  const update = decodeSessionUpdate({
    sessionUpdate: "current_mode_update",
    currentModeId: "plan"
  })
  expect(update).toBeInstanceOf(CurrentModeUpdate)
  expect(modeIdFromUpdate(update as CurrentModeUpdate)).toBe("plan")
})

it("does not fail the fiber on an unknown sessionUpdate", () => {
  const update = decodeSessionUpdate({
    sessionUpdate: "brand_new_event",
    extra: true
  })
  expect(update).toBeInstanceOf(UnknownUpdate)
  expect(update.sessionUpdate).toBe("brand_new_event")
})

it("wraps non-objects as UnknownUpdate", () => {
  const update = decodeSessionUpdate(null)
  expect(update.sessionUpdate).toBe("unknown")
})
