import { expect, it } from "@effect/vitest"
import {
  hostMsgsFromSessionUpdate,
  permissionCardFromParams,
  slashCommandsFromUnknown,
} from "../src/session-messages.ts"

it("maps agent_thought_chunk to thoughtChunk with the full text", () => {
  const msgs = hostMsgsFromSessionUpdate({
    sessionId: "sess_1",
    update: {
      sessionUpdate: "agent_thought_chunk",
      content: { type: "text", text: "Considering the selection.\n" },
    },
  }, "turn_1")
  expect(msgs).toEqual([{
    _tag: "thoughtChunk",
    turnId: "turn_1",
    text: "Considering the selection.\n",
  }])
})

it("maps consecutive thought payloads as separate chunks for the same turn", () => {
  const first = hostMsgsFromSessionUpdate({
    update: { sessionUpdate: "agent_thought_chunk", content: { text: "A" } },
  }, "turn_1")
  const second = hostMsgsFromSessionUpdate({
    update: { sessionUpdate: "agent_thought_chunk", content: { text: "B" } },
  }, "turn_1")
  expect(first[0]).toMatchObject({ _tag: "thoughtChunk", text: "A" })
  expect(second[0]).toMatchObject({ _tag: "thoughtChunk", text: "B" })
})

it("maps agent_message_chunk to agentChunk", () => {
  const msgs = hostMsgsFromSessionUpdate({
    update: {
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "hello" },
    },
  }, "turn_1")
  expect(msgs[0]?._tag).toBe("agentChunk")
})

it("does not fail the fiber on an unknown sessionUpdate", () => {
  const msgs = hostMsgsFromSessionUpdate({
    update: { sessionUpdate: "brand_new_event", extra: true },
  }, "turn_1")
  expect(msgs).toEqual([])
})

it("reads slash commands including input.hint", () => {
  const commands = slashCommandsFromUnknown([
    { name: "compact", description: "Compact context", input: { hint: "instructions" } },
    { name: "always-approve", description: "Skip permission prompts" },
    { skip: true },
  ])
  expect(commands).toEqual([
    { name: "compact", description: "Compact context", hint: "instructions" },
    { name: "always-approve", description: "Skip permission prompts" },
  ])
})

it("builds a permission card with a diff flag", () => {
  const card = permissionCardFromParams({
    toolCall: {
      toolCallId: "call_1",
      title: "Edit",
      content: [{ type: "diff", path: "/tmp/file.ts", oldText: "old", newText: "new" }],
    },
    options: [{ optionId: "allow-once", name: "Allow once", kind: "allow_once" }],
  }, "perm-1")
  expect(card.hasDiff).toBe(true)
  expect(card.toolCallId).toBe("call_1")
  expect(card.options[0]?.optionId).toBe("allow-once")
})

it("flags a permission card as having a diff from rawInput", () => {
  const card = permissionCardFromParams({
    toolCall: {
      toolCallId: "call_2",
      title: "Edit",
      rawInput: { path: "/tmp/file.ts", old_string: "old", new_string: "new" },
    },
    options: [],
  }, "perm-2")
  expect(card.hasDiff).toBe(true)
})
