# Grok's Beard

**Effect-first** VS Code / Cursor client for Grok Build. Named for the thing you grow while the agent works, and a nod to the prog-rock band [Spock's Beard](https://en.wikipedia.org/wiki/Spock%27s_Beard).

Created by [Russell White](https://github.com/russwyte). Published as **`early-effect.groks-beard`**.

> **Status: early / 0.1.** Live on the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard) and [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard). Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

Two ways to use the same Grok, on one typed core: editor chat in the sidebar, and an optional MCP sidecar for the *external* Grok TUI (Ghostty, iTerm, WezTerm). Shared identity is the CLI's session tree under `~/.grok/sessions/`.

## Install

1. Install the [Grok Build CLI](https://x.ai/cli) (`grok`) and sign in with `grok login` or `XAI_API_KEY`.
2. In the Extensions view, search **Grok's Beard**, or run `ext install early-effect.groks-beard`.
3. Open chat with `Ctrl+;` / `Cmd+;`.

Same VSIX on VS Code and Cursor. VS Code installs from the [Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard). Cursor's gallery is [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard) (`early-effect.groks-beard`).

From source:

```bash
pnpm install
pnpm pack:vscode
code --install-extension packages/vscode/groks-beard.vsix --force
```

## Requirements

- VS Code 1.105+ or Cursor
- `grok` on your PATH
- SuperGrok / X Premium+, or an xAI API key. Grok's free tier does not include the CLI agent.

The extension never holds your key.

## What you get

- TUI-shaped composer: `@path`, `@path:start-end`, slash commands from the live CLI
- Ask-mode **review before write**: native multi-file diff, then Allow
- Turn-grouped **Grok Changes**: Keep / Undo per file
- Opt-in TUI bridge: selection, reveal, path-based diffs. No writes through MCP

Not in v1: Electron desktop, phone remote, voice, Codex/Claude, telemetry.

## Shortcuts

| Action | Key |
| --- | --- |
| Open Grok's Beard | `Ctrl+;` / `Cmd+;` |
| Add selection to chat | `Ctrl+Shift+;` / `Cmd+Shift+;` |
| Stop the running turn | `Escape` (chat focused) |

## Copyright and license

Copyright [Russell White](https://github.com/russwyte).

Grok's Beard is an original work by Russell White, published as `early-effect.groks-beard`.

Licensed under the [Apache License, Version 2.0](https://github.com/early-effect/groks-beard/blob/main/LICENSE). You may not use this project except in compliance with the License. Source: [github.com/early-effect/groks-beard](https://github.com/early-effect/groks-beard).
