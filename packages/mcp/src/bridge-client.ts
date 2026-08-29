import { MCP_EDITOR_DOWN_MESSAGE, McpEditorDown } from "@groks-beard/core"
import net from "node:net"
import { BridgeRequest, decodeBridgeResponse, encodeBridgeRequest } from "./bridge-protocol.js"
import { encodeNdjson, splitNdjson } from "./ndjson.js"

export type ConnectBridge = (address: string) => Promise<net.Socket>

const isPipe = (address: string): boolean =>
  address.startsWith("\\\\.\\pipe\\") || address.startsWith("//./pipe/")

export const connectBridge: ConnectBridge = (address) =>
  new Promise((resolve, reject) => {
    const socket = isPipe(address) ? net.connect(address) : net.connect({ path: address })
    const onError = (error: Error) => {
      socket.destroy()
      reject(error)
    }
    socket.setTimeout(2000, () => {
      onError(new Error("bridge connect timeout"))
    })
    socket.once("connect", () => {
      socket.setTimeout(0)
      socket.off("error", onError)
      resolve(socket)
    })
    socket.once("error", onError)
  })

const readLine = (socket: net.Socket): Promise<string> =>
  new Promise((resolve, reject) => {
    let buffer = ""
    const onData = (chunk: string | Buffer) => {
      const split = splitNdjson(buffer, typeof chunk === "string" ? chunk : chunk.toString("utf8"))
      buffer = split.rest
      const line = split.lines[0]
      if (line === undefined) return
      cleanup()
      resolve(line)
    }
    const onEnd = () => {
      cleanup()
      reject(new Error("bridge closed"))
    }
    const onError = (error: Error) => {
      cleanup()
      reject(error)
    }
    const cleanup = () => {
      socket.off("data", onData)
      socket.off("end", onEnd)
      socket.off("error", onError)
    }
    socket.setEncoding("utf8")
    socket.on("data", onData)
    socket.once("end", onEnd)
    socket.once("error", onError)
  })

export const probeBridge = async (
  address: string,
  workspace: string,
  connect: ConnectBridge = connectBridge,
): Promise<void> => {
  try {
    const socket = await connect(address)
    socket.destroy()
  } catch {
    throw new McpEditorDown({ workspace })
  }
}

export const callBridge = async (
  address: string,
  workspace: string,
  request: BridgeRequest,
  connect: ConnectBridge = connectBridge,
): Promise<unknown> => {
  let socket: net.Socket
  try {
    socket = await connect(address)
  } catch {
    throw new McpEditorDown({ workspace })
  }
  try {
    socket.write(encodeNdjson(encodeBridgeRequest(
      new BridgeRequest({
        id: request.id,
        tool: request.tool,
        ...(request.args !== undefined ? { args: request.args } : {}),
      }),
    )))
    const line = await readLine(socket)
    const response = decodeBridgeResponse(JSON.parse(line))
    if (!response.ok) {
      throw new Error(response.error?.message ?? MCP_EDITOR_DOWN_MESSAGE)
    }
    return response.result
  } finally {
    socket.end()
    socket.destroy()
  }
}
