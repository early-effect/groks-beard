import { expect, it } from "@effect/vitest"
import { HOST_MSG_TAGS } from "@groks-beard/core"
import { applyHostMsg, emptyChatModel, HOST_MSG_APPLIED } from "../src/model.ts"
import { thoughtBlock, thoughtSummaryLabel } from "../src/thinking.ts"

it("applyHostMsg handles every closed HostMsg tag", () => {
  expect(Object.keys(HOST_MSG_APPLIED).sort()).toEqual([...HOST_MSG_TAGS].sort())
})

it("concatenates thought chunks into one expandable stream per turn", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, {
    _tag: "userMessage",
    turnId: "turn_1",
    text: "explain this",
    chips: [],
  })
  model = applyHostMsg(model, {
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Considering the selection.\n",
  })
  model = applyHostMsg(model, {
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Then I'll answer.\n",
  })
  model = applyHostMsg(model, {
    _tag: "agentChunk",
    turnId: "turn_1",
    text: "hello",
  })
  const turn = model.turns[0]
  expect(turn?.thought).toBe("Considering the selection.\nThen I'll answer.\n")
  expect(turn?.agent).toBe("hello")
  const block = thoughtBlock(turn?.thought ?? "", false, false)
  expect(block?.open).toBe(false)
  expect(block?.stream).toBe("Considering the selection.\nThen I'll answer.\n")
  expect(block?.summary.startsWith("Thinking:")).toBe(true)
})

it("keeps the full thought stream after the turn ends", () => {
  let model = emptyChatModel()
  model = applyHostMsg(model, { _tag: "thoughtChunk", turnId: "t", text: "step one\nstep two\n" })
  model = applyHostMsg(model, { _tag: "turnEnd", turnId: "t", stopReason: "end_turn" })
  const turn = model.turns[0]
  expect(thoughtSummaryLabel(turn?.thought ?? "", true).startsWith("Thought:")).toBe(true)
  expect(thoughtBlock(turn?.thought ?? "", true, true)?.stream).toContain("step two")
})

it("does not build a thought block when the stream is empty", () => {
  expect(thoughtBlock("", false, false)).toBeUndefined()
})
