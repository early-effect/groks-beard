import { expect, it } from "@effect/vitest"
import { WEBVIEW_MSG_TAGS } from "@groks-beard/core"
import {
  dispatchWebviewMsg,
  WEBVIEW_DISPATCH_HANDLED,
  type WebviewHandlers,
} from "../src/host-dispatch.ts"

it("dispatches every closed WebviewMsg tag", () => {
  expect(Object.keys(WEBVIEW_DISPATCH_HANDLED).sort()).toEqual([...WEBVIEW_MSG_TAGS].sort())
  const seen: Array<string> = []
  const handlers = Object.fromEntries(
    WEBVIEW_MSG_TAGS.map((tag) => [tag, () => seen.push(tag)]),
  ) as unknown as WebviewHandlers
  for (const tag of WEBVIEW_MSG_TAGS) {
    dispatchWebviewMsg({ _tag: tag } as never, handlers)
  }
  expect(seen.sort()).toEqual([...WEBVIEW_MSG_TAGS].sort())
})
