import { parseWorkspaceArg, runMcpProxy } from "./mcp-stdio.js"

export const runMcpProxyMain = async (argv: ReadonlyArray<string>): Promise<void> => {
  const workspace = parseWorkspaceArg(argv)
  if (workspace === undefined || workspace === "") {
    process.stderr.write("mcp-proxy: missing --workspace <absolute-path>\n")
    process.exit(2)
  }
  const code = await runMcpProxy({
    workspace,
    stdin: process.stdin,
    stdout: process.stdout,
    stderr: process.stderr,
  })
  process.exit(code)
}
