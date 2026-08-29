import { expect, it } from "@effect/vitest"
import { decodeSessionSummary } from "../src/session-summary.ts"

it("decodes observed summary.json keys and strips extras", () => {
  const summary = decodeSessionSummary({
    info: { id: "abc", cwd: "/tmp/proj" },
    generated_title: "Plan the beard",
    current_model_id: "grok-4.6",
    grok_home: "/Users/russ/.grok",
    unexpected_future_field: "ok"
  })
  expect(summary.info.id).toBe("abc")
  expect(summary.generated_title).toBe("Plan the beard")
  expect(summary.current_model_id).toBe("grok-4.6")
  expect("unexpected_future_field" in summary).toBe(false)
})

it("treats optional fields as absent, not required", () => {
  const summary = decodeSessionSummary({
    info: { id: "x", cwd: "/r" }
  })
  expect(summary.title_is_manual).toBeUndefined()
  expect(summary.parent_session_id).toBeUndefined()
})
