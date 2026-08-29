# Grok's Beard

**Effect-first** VS Code / Cursor client for [Grok Build](https://x.ai). Named for the thing you grow while the agent works.

> **Status: early / pre-1.0.** Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

Two ways to use the same Grok, on one typed core:

| | **Editor chat** | **TUI sidecar** |
| --- | --- | --- |
| **What** | Sidebar in VS Code / Cursor over `grok agent stdio` | MCP tools the *external* Grok TUI (Ghostty, iTerm, WezTerm) can call |
| **Best when** | You want selection-into-chat, slash/`@` like the TUI, native diffs | You want to stay in the TUI and use the editor as eyes |

Shared identity is the CLI's session tree under `~/.grok/sessions/`. Start in the TUI, resume in the editor, and the reverse. The TUI is not hosted inside VS Code's terminal (xterm.js is a documented-bad host for Grok).

## What you get (v1)

- Grok Build CLI only. Sign in with `grok login` or `XAI_API_KEY`. The extension never holds your key.
- TUI-shaped composer: `@path`, `@path:start-end`, slash commands from the live CLI.
- Ask-mode **review before write**: native multi-file diff, then Allow.
- Turn-grouped **Grok Changes**: Keep / Undo per file, including always-approve turns.
- Opt-in TUI bridge: selection, reveal, path-based diffs. No writes through MCP.

Not in v1: Electron desktop, phone remote, voice, Codex/Claude, telemetry.

## Requirements

- VS Code 1.105+ or Cursor (same VSIX).
- The [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH.
- A SuperGrok / X Premium+ login, or an xAI API key. Grok's free tier does not include the CLI agent.

## Install (when a VSIX exists)

From source, once the workspace is built:

```bash
pnpm install
pnpm exec tsc -b
# then install the packaged VSIX into VS Code or Cursor
```

Until then this repo is the design and the upcoming implementation. Open **Grok's Beard** with `Ctrl+;` / `Cmd+;`.

## Modules (planned)

| Package | Role |
| --- | --- |
| `@groks-beard/core` | Schema, tagged errors, chips, change-sets, session index |
| `@groks-beard/acp` | Effect wrapper around `grok agent stdio` |
| `@groks-beard/mcp` | TUI sidecar tools and stdio proxy |
| `@groks-beard/vscode` | Extension host, diffs, commands |
| `@groks-beard/webview` | Chat UI (dumb renderer) |

Stack: Effect 3, pnpm workspaces, vitest. The CLI owns tools, skills, MCP, memory, and compaction.

## License

TBD. Treat this as source-available until a license file lands.
