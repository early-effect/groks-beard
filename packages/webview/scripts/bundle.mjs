import * as esbuild from "esbuild"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(fileURLToPath(import.meta.url))
const outfile = join(root, "../../vscode/dist/webview/chat.js")

await esbuild.build({
  absWorkingDir: join(root, ".."),
  entryPoints: ["src/main.ts"],
  bundle: true,
  format: "iife",
  platform: "browser",
  outfile,
  legalComments: "none",
})
