import { expect, it } from "@effect/vitest"
import {
  decodeHostMsg,
  decodeWebviewMsg,
  displayStopReason,
  HOST_MSG_HANDLED,
  HOST_MSG_TAGS,
  WEBVIEW_MSG_HANDLED,
  WEBVIEW_MSG_TAGS
} from "../src/protocol.ts"

it("locks the closed HostMsg tag list", () => {
  expect(HOST_MSG_TAGS.length).toBe(Object.keys(HOST_MSG_HANDLED).length)
  expect(HOST_MSG_HANDLED.restoreTranscript).toBe(true)
})

it("locks the closed WebviewMsg tag list", () => {
  expect(WEBVIEW_MSG_TAGS.length).toBe(Object.keys(WEBVIEW_MSG_HANDLED).length)
  expect(WEBVIEW_MSG_HANDLED.cycleMode).toBe(true)
})

it("decodes a permission card and a send", () => {
  const card = decodeHostMsg({
    _tag: "permissionCard",
    requestId: "1",
    toolCallId: "c1",
    title: "Edit",
    options: [{ optionId: "allow-once", name: "Allow once", kind: "allow_once" }],
    hasDiff: true
  })
  expect(card._tag).toBe("permissionCard")
  const send = decodeWebviewMsg({
    _tag: "send",
    text: "hello",
    chips: []
  })
  expect(send._tag).toBe("send")
})

it("maps unknown stop reasons to unknown", () => {
  expect(displayStopReason("end_turn")).toBe("end_turn")
  expect(displayStopReason("tool_use")).toBe("unknown")
})
