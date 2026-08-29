#!/usr/bin/env node
import readline from "node:readline"

if (process.argv.includes("--always-approve") || process.argv.includes("--yolo")) {
  process.stderr.write("fixture refuses yolo\n")
  process.exit(3)
}

if (process.argv.includes("--version")) {
  process.stdout.write("grok 1.0.13 (5e9a58528b76) [stable]\n")
  process.exit(0)
}

const rl = readline.createInterface({ input: process.stdin })
rl.on("line", (line) => {
  if (line.trim() === "") return
  const msg = JSON.parse(line)
  const reply = (payload) => {
    process.stdout.write(`${JSON.stringify(payload)}\n`)
  }
  if (msg.method === "initialize") {
    reply({
      jsonrpc: "2.0",
      id: msg.id,
      result: { protocolVersion: 1, agentCapabilities: { loadSession: true } }
    })
    return
  }
  if (msg.method === "session/new") {
    reply({ jsonrpc: "2.0", id: msg.id, result: { sessionId: "sess_fake" } })
    return
  }
  if (msg.method === "session/load") {
    reply({ jsonrpc: "2.0", id: msg.id, result: { sessionId: msg.params.sessionId } })
    return
  }
  if (msg.method === "session/prompt") {
    reply({
      jsonrpc: "2.0",
      method: "session/update",
      params: {
        sessionId: msg.params.sessionId,
        update: {
          sessionUpdate: "agent_message_chunk",
          content: { type: "text", text: "ok" }
        }
      }
    })
    reply({ jsonrpc: "2.0", id: msg.id, result: { stopReason: "end_turn" } })
    return
  }
  reply({
    jsonrpc: "2.0",
    id: msg.id,
    error: { code: -32601, message: `Method not found: ${msg.method}` }
  })
})
