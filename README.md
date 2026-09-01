# Grok's Beard

**Effect-first** VS Code / Cursor client for [Grok Build](https://x.ai). Named for the thing you grow while the agent works, and a nod to the prog-rock band [Spock's Beard](https://en.wikipedia.org/wiki/Spock%27s_Beard).

Created by [Russell White](https://github.com/russwyte). Published as **`early-effect.groks-beard`**.

> **Status: early / 0.1.** Live on the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard) and [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard). Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

Two ways to use the same Grok, on one typed core:

| | **Editor chat** | **TUI sidecar** |
| --- | --- | --- |
| **What** | Sidebar in VS Code / Cursor over `grok agent stdio` | MCP tools the *external* Grok TUI (Ghostty, iTerm, WezTerm) can call |
| **Best when** | You want selection-into-chat, slash/`@` like the TUI, native diffs | You want to stay in the TUI and use the editor as eyes |

Shared identity is the CLI's session tree under `~/.grok/sessions/`. Start in the TUI, resume in the editor, and the reverse. The TUI is not hosted inside VS Code's terminal (xterm.js is a documented-bad host for Grok).

## Install

1. Install the [Grok Build CLI](https://x.ai/cli) (`grok`) and sign in with `grok login` or `XAI_API_KEY`.
2. Install the extension.
3. Open chat with `Ctrl+;` / `Cmd+;`.

**VS Code** (Visual Studio Marketplace):

```text
ext install early-effect.groks-beard
```

https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard

**Cursor** (Open VSX; that is what Cursor's Extensions search uses):

Search **Grok's Beard** in Cursor, or install `early-effect.groks-beard`.

https://open-vsx.org/extension/early-effect/groks-beard

**From source:**

```bash
pnpm install
pnpm pack:vscode
code --install-extension packages/vscode/groks-beard.vsix --force
# Cursor: cursor --install-extension packages/vscode/groks-beard.vsix --force
```

## Requirements

- VS Code 1.105+ or Cursor (same VSIX).
- The [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH.
- A SuperGrok / X Premium+ login, or an xAI API key. Grok's free tier does not include the CLI agent.

The extension never holds your key.

## What you get (v1)

- Grok Build CLI only. Sign in with `grok login` or `XAI_API_KEY`.
- TUI-shaped composer: `@path`, `@path:start-end`, slash commands from the live CLI.
- Ask-mode **review before write**: native multi-file diff, then Allow.
- Turn-grouped **Grok Changes**: Keep / Undo per file, including always-approve turns.
- Opt-in TUI bridge: selection, reveal, path-based diffs. No writes through MCP.

Not in v1: Electron desktop, phone remote, voice, Codex/Claude, telemetry.

## Shortcuts

| Action | Key |
| --- | --- |
| Open Grok's Beard | `Ctrl+;` / `Cmd+;` |
| Add selection to chat | `Ctrl+Shift+;` / `Cmd+Shift+;` |
| Stop the running turn | `Escape` (chat focused) |

## Modules

| Package | Role |
| --- | --- |
| `@groks-beard/core` | Schema, tagged errors, chips, change-sets, session index |
| `@groks-beard/acp` | Effect wrapper around `grok agent stdio` |
| `@groks-beard/mcp` | TUI sidecar tools and stdio proxy |
| `@groks-beard/vscode` | Extension host, diffs, commands |
| `@groks-beard/webview` | Chat UI (dumb renderer) |

Stack: Effect 4 RC, TypeScript 7, pnpm workspaces, vitest. The CLI owns tools, skills, MCP, memory, and compaction.

## Copyright and license

Copyright [Russell White](https://github.com/russwyte).

Grok's Beard is an original work by Russell White, published as `early-effect.groks-beard`.

Licensed under the [Apache License, Version 2.0](LICENSE). You may not use this project except in compliance with the License.
