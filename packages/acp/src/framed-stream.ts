import type { AnyMessage, Stream } from "@agentclientprotocol/sdk"
import { isJsonRpcRequest, isJsonRpcResponse, isSuccessResponse } from "./jsonrpc-line.js"
import { COMMIT_BEFORE_CONTINUE } from "./methods.js"
import { splitNdjson } from "./ndjson.js"
import { commitMode, type SessionState } from "./session-state.js"

type Pending = {
  readonly method: string
  readonly modeId?: string
}

export type FramedTransport = {
  readonly stream: Stream
  readonly state: SessionState
  readonly feedFromAgent: (bytes: Uint8Array) => void
  readonly close: (error?: unknown) => void
}

export const createFramedTransport = (
  state: SessionState,
  options: { readonly onOutgoing?: (message: AnyMessage) => void } = {}
): FramedTransport => {
  const pending = new Map<string | number, Pending>()
  let buffer = ""
  let readableController: ReadableStreamDefaultController<AnyMessage> | undefined
  const decoder = new TextDecoder()

  const readable = new ReadableStream<AnyMessage>({
    start(controller) {
      readableController = controller
    }
  })

  const writable = new WritableStream<AnyMessage>({
    write(message) {
      if (isJsonRpcRequest(message) && message.id !== null) {
        const modeId =
          message.method === "session/set_mode" &&
            typeof message.params === "object" &&
            message.params !== null &&
            "modeId" in message.params
            ? String((message.params as { modeId: unknown }).modeId)
            : undefined
        pending.set(message.id, {
          method: message.method,
          ...(modeId !== undefined ? { modeId } : {})
        })
      }
      options.onOutgoing?.(message)
    }
  })

  const feedLine = (line: string): void => {
    const message = JSON.parse(line) as AnyMessage
    if (isJsonRpcResponse(message) && message.id !== null) {
      const recorded = pending.get(message.id)
      pending.delete(message.id)
      if (
        recorded !== undefined &&
        COMMIT_BEFORE_CONTINUE.has(recorded.method) &&
        isSuccessResponse(message) &&
        recorded.modeId !== undefined
      ) {
        commitMode(state, recorded.modeId)
      }
    }
    readableController?.enqueue(message)
  }

  return {
    stream: { readable, writable },
    state,
    feedFromAgent: (bytes) => {
      const split = splitNdjson(buffer, decoder.decode(bytes, { stream: true }))
      buffer = split.rest
      for (const line of split.lines) feedLine(line)
    },
    close: (error) => {
      if (error !== undefined) readableController?.error(error)
      else readableController?.close()
    }
  }
}
