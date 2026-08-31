import { execFile } from "node:child_process"
import {
  firstJsonValue,
  grokMcpCliArgs,
  type McpDoctorReport,
  parseDoctorJson,
} from "./grok-mcp.js"

export const MCP_DOCTOR_TIMEOUT_MS = 25_000
export const MCP_TRUST_TIMEOUT_MS = 15_000

const runGrok = (
  command: string,
  args: ReadonlyArray<string>,
  cwd: string,
  timeoutMs: number,
  timeoutLabel: string,
): Promise<{ readonly code: number; readonly stdout: string; readonly stderr: string }> =>
  new Promise((resolve, reject) => {
    execFile(
      command,
      [...args],
      { cwd, timeout: timeoutMs, encoding: "utf8", windowsHide: true },
      (error, stdout, stderr) => {
        const out = typeof stdout === "string" ? stdout : ""
        const err = typeof stderr === "string" ? stderr : ""
        if (error && "killed" in error && (error as { killed?: boolean }).killed === true) {
          reject(new Error(`${timeoutLabel} timed out`))
          return
        }
        const code =
          error && "code" in error && typeof (error as { code?: unknown }).code === "number"
            ? (error as { code: number }).code
            : 0
        resolve({ code, stdout: out, stderr: err })
      },
    )
  })

export const loadMcpCatalog = async (
  command: string,
  cwd: string,
  options: { readonly trustFolder?: boolean; readonly name?: string } = {},
): Promise<McpDoctorReport> => {
  const doctor = options.name !== undefined && options.name !== ""
    ? ["doctor", options.name, "--json"]
    : ["doctor", "--json"]
  const result = await runGrok(
    command,
    grokMcpCliArgs(doctor, options.trustFolder === true),
    cwd,
    MCP_DOCTOR_TIMEOUT_MS,
    "grok mcp",
  )
  try {
    return parseDoctorJson(firstJsonValue(result.stdout))
  } catch (cause) {
    const extra = result.stderr.trim()
    const message = cause instanceof Error ? cause.message : String(cause)
    throw new Error(extra === "" ? message : `${message}: ${extra.slice(0, 240)}`)
  }
}

export const grantFolderTrust = async (command: string, cwd: string): Promise<void> => {
  const result = await runGrok(
    command,
    ["--trust", "inspect", "--json"],
    cwd,
    MCP_TRUST_TIMEOUT_MS,
    "grok --trust inspect",
  )
  if (result.code === 0) return
  const detail = (result.stderr.trim() || result.stdout.trim()).slice(0, 240)
  throw new Error(detail === "" ? "grok --trust inspect failed" : detail)
}

export const setMcpServerEnabled = async (
  command: string,
  cwd: string,
  name: string,
  enabled: boolean,
): Promise<void> => {
  const action = enabled ? "enable" : "disable"
  const result = await runGrok(
    command,
    grokMcpCliArgs([action, name]),
    cwd,
    10_000,
    "grok mcp",
  )
  if (result.code === 0) return
  const detail = (result.stderr.trim() || result.stdout.trim()).slice(0, 240)
  throw new Error(detail === "" ? `grok mcp ${action} ${name} failed` : detail)
}
