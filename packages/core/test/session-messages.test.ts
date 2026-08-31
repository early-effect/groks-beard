import { expect, it } from "@effect/vitest"
import {
  hostMsgsFromSessionUpdate,
  overlayModelCatalog,
  parseModelReasoning,
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

it("maps usage_update to sessionMeta occupancy", () => {
  const msgs = hostMsgsFromSessionUpdate({
    sessionId: "s",
    update: { sessionUpdate: "usage_update", used: 80, size: 500 },
  }, "turn_1")
  expect(msgs).toEqual([{
    _tag: "sessionMeta",
    sessionId: "s",
    title: "",
    modeId: "",
    occupancy: { used: 80, size: 500 },
  }])
})

it("maps config_option_update thought_level to sessionMeta reasoning", () => {
  const msgs = hostMsgsFromSessionUpdate({
    sessionId: "s",
    update: {
      sessionUpdate: "config_option_update",
      configOptions: [{
        id: "reasoning_effort",
        name: "Reasoning",
        category: "thought_level",
        currentValue: "high",
        options: [
          { value: "low", name: "Low" },
          { value: "high", name: "High" },
        ],
      }],
    },
  }, "turn_1")
  expect(msgs[0]).toMatchObject({
    _tag: "sessionMeta",
    reasoning: { id: "reasoning_effort", current: "high" },
  })
})

it("parses catalog reasoning_efforts with label and default", () => {
  expect(parseModelReasoning({
    supports_reasoning_effort: true,
    reasoning_effort: "high",
    reasoning_efforts: [
      { id: "xhigh", value: "xhigh", label: "Extra High Effort", default: false },
      { id: "high", value: "high", label: "High Effort", default: true },
    ],
  })).toEqual({
    current: "high",
    options: [
      { value: "xhigh", name: "Extra High Effort" },
      { value: "high", name: "High Effort" },
    ],
  })
})

it("reads model reasoning from _meta and skips models with no efforts", () => {
  expect(
    parseModelReasoning({
      modelId: "grok-4.6",
      _meta: {
        reasoning_efforts: [{ value: "low", name: "Low" }, { value: "high", name: "High" }],
        reasoning_effort: "low",
      },
    })?.current,
  ).toBe("low")
  expect(parseModelReasoning({
    modelId: "vertigo-qwen",
    name: "Qwen 3.8 27B",
    supports_reasoning_effort: false,
  })).toBeUndefined()
})

it("overlays catalog effort menus onto advertised models", () => {
  const overlaid = overlayModelCatalog(
    [
      { modelId: "grok-4.6", name: "Grok 4.6" },
      { modelId: "vertigo-qwen", name: "Qwen 3.8 27B" },
    ],
    {
      models: {
        "grok-4.6": {
          info: {
            context_window: 500000,
            reasoning_effort: "high",
            reasoning_efforts: [{ value: "high", label: "High Effort", default: true }],
          },
        },
      },
    },
  )
  expect(overlaid[0]).toMatchObject({
    modelId: "grok-4.6",
    contextWindow: 500000,
    reasoning: { current: "high" },
  })
  expect(overlaid[1]?.reasoning).toBeUndefined()
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

it("maps a tool_call title and leaves a status-only update blank", () => {
  const start = hostMsgsFromSessionUpdate({
    update: {
      sessionUpdate: "tool_call",
      toolCallId: "c1",
      title: "search_tool",
      rawInput: { query: "metals" },
    },
  }, "turn_1")
  expect(start[0]).toMatchObject({
    _tag: "toolGroup",
    turnId: "turn_1",
    tools: [{
      id: "c1",
      title: "search_tool",
      kind: "",
      status: "pending",
      input: expect.stringContaining("metals"),
    }],
  })
  const done = hostMsgsFromSessionUpdate({
    update: {
      sessionUpdate: "tool_call_update",
      toolCallId: "c1",
      status: "completed",
    },
  }, "turn_1")
  expect(done[0]).toMatchObject({
    _tag: "toolGroup",
    tools: [{ id: "c1", title: "", kind: "", status: "completed" }],
  })
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
