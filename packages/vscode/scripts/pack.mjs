import { spawnSync } from "node:child_process"
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { createRequire } from "node:module"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const vscodeRoot = join(dirname(fileURLToPath(import.meta.url)), "..")
const repoRoot = join(vscodeRoot, "../..")
const esbuild = createRequire(join(repoRoot, "packages/webview/package.json"))("esbuild")

const bundle = (entry, outfile) =>
  esbuild.buildSync({
    absWorkingDir: vscodeRoot,
    entryPoints: [entry],
    bundle: true,
    platform: "node",
    format: "esm",
    outfile,
    external: ["vscode"],
    legalComments: "none",
    logLevel: "info",
  })

bundle("src/extension.ts", "dist/extension.js")
bundle("src/mcp-proxy.ts", "dist/mcp-proxy.js")

const chatJs = join(vscodeRoot, "dist/webview/chat.js")
if (!existsSync(chatJs)) {
  throw new Error("dist/webview/chat.js missing. Run pnpm build first.")
}

const stage = join(vscodeRoot, ".vsix-stage")
rmSync(stage, { recursive: true, force: true })
mkdirSync(join(stage, "dist/webview"), { recursive: true })
mkdirSync(join(stage, "media"), { recursive: true })

const pkg = JSON.parse(readFileSync(join(vscodeRoot, "package.json"), "utf8"))
pkg.name = "groks-beard"
delete pkg.private
writeFileSync(join(stage, "package.json"), `${JSON.stringify(pkg, null, 2)}\n`)
const readme = join(vscodeRoot, "README.md")
const license = join(repoRoot, "LICENSE")
if (!existsSync(readme)) throw new Error("packages/vscode/README.md missing")
if (!existsSync(license)) throw new Error("LICENSE missing at repo root")
cpSync(readme, join(stage, "README.md"))
cpSync(license, join(stage, "LICENSE"))
cpSync(join(vscodeRoot, "dist/extension.js"), join(stage, "dist/extension.js"))
cpSync(join(vscodeRoot, "dist/mcp-proxy.js"), join(stage, "dist/mcp-proxy.js"))
cpSync(chatJs, join(stage, "dist/webview/chat.js"))
cpSync(join(vscodeRoot, "media/beard.svg"), join(stage, "media/beard.svg"))
cpSync(join(vscodeRoot, "media/logo.png"), join(stage, "media/logo.png"))
cpSync(
  join(vscodeRoot, "media/markdown-preview-selection.js"),
  join(stage, "media/markdown-preview-selection.js"),
)
writeFileSync(
  join(stage, ".vscodeignore"),
  "*\n!package.json\n!README.md\n!LICENSE\n!media/**\n!dist/extension.js\n!dist/mcp-proxy.js\n!dist/webview/chat.js\n",
)

const vsce = spawnSync(
  "npx",
  [
    "--yes",
    "@vscode/vsce",
    "package",
    "--no-dependencies",
    "--out",
    join(vscodeRoot, "groks-beard.vsix"),
  ],
  { cwd: stage, stdio: "inherit" },
)
rmSync(stage, { recursive: true, force: true })
if (vsce.status !== 0) process.exit(vsce.status ?? 1)
