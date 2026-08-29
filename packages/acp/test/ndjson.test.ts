import { expect, it } from "@effect/vitest"
import { encodeNdjsonChunk, splitNdjson } from "../src/ndjson.ts"

it("splits a two-line chunk and keeps a partial rest", () => {
  const first = splitNdjson("", "{\"a\":1}\n{\"b\":2}\n{\"c\":")
  expect(first.lines).toEqual(['{"a":1}', '{"b":2}'])
  expect(first.rest).toBe('{"c":')
  const second = splitNdjson(first.rest, "3}\n")
  expect(second.lines).toEqual(['{"c":3}'])
  expect(second.rest).toBe("")
})

it("encodes multiple values as one newline-delimited chunk", () => {
  const bytes = encodeNdjsonChunk([{ id: 1 }, { id: 2 }])
  expect(new TextDecoder().decode(bytes)).toBe("{\"id\":1}\n{\"id\":2}\n")
})
