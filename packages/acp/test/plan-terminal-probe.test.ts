import { expect, it } from "@effect/vitest"
import {
  PLAN_TERMINAL_MUTATION_DELEGATED,
  planTerminalAllowlistArmed,
} from "../src/plan-terminal-probe.ts"

it("records the Plan-mode terminal probe and keeps the allowlist armed when unverified", () => {
  expect(
    PLAN_TERMINAL_MUTATION_DELEGATED === true
      || PLAN_TERMINAL_MUTATION_DELEGATED === false
      || PLAN_TERMINAL_MUTATION_DELEGATED === "unverified",
  ).toBe(true)
  expect(planTerminalAllowlistArmed("unverified")).toBe(true)
  expect(planTerminalAllowlistArmed(true)).toBe(true)
  expect(planTerminalAllowlistArmed(false)).toBe(false)
})
