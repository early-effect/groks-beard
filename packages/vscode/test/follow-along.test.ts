import { expect, it } from "@effect/vitest"
import { planFollowAlong } from "../src/follow-along.ts"
import { ORIGINAL_SCHEME } from "../src/virtual-docs.ts"

it("preserves focus when the user is in a Beard diff editor", () => {
  const plan = planFollowAlong(
    [{ path: "/tmp/a.ts", line: 4 }],
    { scheme: ORIGINAL_SCHEME }
  )
  expect(plan.preserveFocus).toBe(true)
  expect(plan.reveals[0]).toEqual({ path: "/tmp/a.ts", line: 4 })
})

it("steals focus for follow-along when the user is in a normal editor", () => {
  const plan = planFollowAlong([{ path: "/tmp/a.ts" }], { scheme: "file" })
  expect(plan.preserveFocus).toBe(false)
})

it("preserves focus for a native multi-diff tab", () => {
  const plan = planFollowAlong([{ path: "/tmp/a.ts", line: 1 }], { inDiffEditor: true })
  expect(plan.preserveFocus).toBe(true)
})
