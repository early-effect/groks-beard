import { expect, it } from "@effect/vitest"
import { grokMethodFromWire, xaiToolFromMeta } from "../src/methods.ts"

it("normalizes x.ai/ methods to the ACP-legal _x.ai/ form", () => {
  expect(grokMethodFromWire("x.ai/exit_plan_mode")).toBe("_x.ai/exit_plan_mode")
  expect(grokMethodFromWire("_x.ai/interject")).toBe("_x.ai/interject")
  expect(grokMethodFromWire("session/prompt")).toBe("session/prompt")
})

it("reads _meta[x.ai/tool]", () => {
  const tool = xaiToolFromMeta({
    "x.ai/tool": { name: "exit_plan_mode", kind: "exit_plan", read_only: true },
  })
  expect(tool?.name).toBe("exit_plan_mode")
  expect(tool?.kind).toBe("exit_plan")
})
