import { expect, it } from "@effect/vitest"
import { CliNotFound, MethodNotFound } from "../src/errors.ts"

it("CliNotFound is tagged", () => {
  const error = new CliNotFound({ searched: ["/bin/grok"] })
  expect(error._tag).toBe("CliNotFound")
  expect(error.searched).toEqual(["/bin/grok"])
})

it("MethodNotFound maps to JSON-RPC -32601", () => {
  const error = new MethodNotFound({ method: "_x.ai/interject" })
  expect(error._tag).toBe("MethodNotFound")
  expect(error.jsonRpcCode).toBe(-32601)
})
