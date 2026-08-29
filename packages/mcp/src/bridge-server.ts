import { chmodSync, mkdirSync, unlinkSync } from "node:fs"
import net from "node:net"
import { dirname } from "node:path"
import {
  type BridgeRequest,
  BridgeResponse,
  decodeBridgeRequest,
  encodeBridgeResponse,
} from "./bridge-protocol.js"
import { encodeNdjson, splitNdjson } from "./ndjson.js"
import { SOCKET_DIR_MODE, SOCKET_MODE } from "./socket-address.js"

export type BridgeHandleFn = (request: BridgeRequest) => Promise<unknown>

export type BridgeServer = {
  readonly address: string
  readonly listening: boolean
  readonly close: () => Promise<void>
}

const isPipe = (address: string): boolean =>
  address.startsWith("\\\\.\\pipe\\") || address.startsWith("//./pipe/")

const idOf = (parsed: unknown): string => {
  if (typeof parsed === "object" && parsed !== null && "id" in parsed) {
    const id = (parsed as { id: unknown }).id
    if (typeof id === "string") return id
    if (typeof id === "number") return String(id)
  }
  return ""
}

const causeMessage = (cause: unknown): string =>
  cause instanceof Error ? cause.message : String(cause)

export const listenBridge = (
  address: string,
  handle: BridgeHandleFn,
): Promise<BridgeServer> =>
  new Promise((resolve, reject) => {
    if (!isPipe(address)) {
      mkdirSync(dirname(address), { recursive: true, mode: SOCKET_DIR_MODE })
      try {
        unlinkSync(address)
      } catch {
        // no stale socket
      }
    }
    const server = net.createServer((socket) => {
      socket.on("error", () => undefined)
      let buffer = ""
      socket.setEncoding("utf8")
      const writeLine = (value: unknown) => {
        if (socket.destroyed || socket.writableEnded) return
        try {
          socket.write(encodeNdjson(value))
        } catch {
          // peer already gone
        }
      }
      const reply = async (line: string) => {
        let parsed: unknown
        try {
          parsed = JSON.parse(line)
        } catch {
          writeLine(encodeBridgeResponse(
            new BridgeResponse({
              id: "",
              ok: false,
              error: { message: "invalid json" },
            }),
          ))
          socket.end()
          return
        }
        try {
          const request = decodeBridgeRequest(parsed)
          const result = await handle(request)
          writeLine(encodeBridgeResponse(
            new BridgeResponse({
              id: request.id,
              ok: true,
              result,
            }),
          ))
        } catch (cause) {
          const tagged = typeof cause === "object" && cause !== null && "_tag" in cause
            ? String((cause as { _tag: unknown })._tag)
            : undefined
          writeLine(encodeBridgeResponse(
            new BridgeResponse({
              id: idOf(parsed),
              ok: false,
              error: {
                message: causeMessage(cause),
                ...(tagged !== undefined ? { _tag: tagged } : {}),
              },
            }),
          ))
        }
        socket.end()
      }
      socket.on("data", (chunk: string) => {
        const split = splitNdjson(buffer, chunk)
        buffer = split.rest
        const line = split.lines[0]
        if (line === undefined) return
        void reply(line)
      })
    })
    server.once("error", reject)
    server.listen(address, () => {
      if (!isPipe(address)) {
        try {
          chmodSync(address, SOCKET_MODE)
        } catch {
          // best-effort 0600
        }
      }
      resolve({
        address,
        listening: true,
        close: () =>
          new Promise((done, fail) => {
            server.close((error) => {
              if (!isPipe(address)) {
                try {
                  unlinkSync(address)
                } catch {
                  // already gone
                }
              }
              if (error) fail(error)
              else done()
            })
          }),
      })
    })
  })
