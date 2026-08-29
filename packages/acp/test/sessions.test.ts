import { expect, it } from "@effect/vitest"
import { SessionLoadFailed, SessionLocked } from "@groks-beard/core"
import { classifySessionLoadError, loadErrorCopy } from "../src/sessions.ts"

it("classifies lock-shaped load failures", () => {
  const locked = classifySessionLoadError(new Error("session locked"), "s1", "/tmp/p")
  expect(locked).toBeInstanceOf(SessionLocked)
  expect(loadErrorCopy(locked).actions).toEqual(["fork", "openTui", "retry"])
})

it("classifies other load failures as SessionLoadFailed", () => {
  const failed = classifySessionLoadError(new Error("no such session"), "s1", "/tmp/p")
  expect(failed).toBeInstanceOf(SessionLoadFailed)
  expect(loadErrorCopy(failed).actions).toEqual(["retry"])
})
