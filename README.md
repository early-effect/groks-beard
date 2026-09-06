# Grok's Beard

VS Code / Cursor client for [Grok Build](https://x.ai). Named for the thing you grow while the agent works, and a nod to the prog-rock band [Spock's Beard](https://en.wikipedia.org/wiki/Spock%27s_Beard).

Created by [Russell White](https://github.com/russwyte). Published as **`early-effect.groks-beard`**.

> **Status: 0.2.0 (Scala).** Dogfood from source. The [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard) and [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard) listings are still the older 0.1 TypeScript build. Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

The product is Scala 3 under `beard/` (Ascent, Scala.js, ZIO). Preview in the browser and the editor sidebar are the same UI.

| | **Editor chat** | **Browser preview** | **TUI sidecar** |
| --- | --- | --- | --- |
| **What** | VS Code / Cursor sidebar over `grok agent stdio` | Same chat at http://localhost:8765/ | MCP tools the *external* Grok TUI can call |
| **Best when** | Daily use, Keep/Undo persist, native diffs | Iterate on chrome without packing a VSIX | Stay in Ghostty / iTerm and use the editor as eyes |

Shared identity is the CLI's session tree under `~/.grok/sessions/`. Start in the TUI, resume in the editor, and the reverse. The TUI is not hosted inside VS Code's terminal (xterm.js is a documented-bad host for Grok).

## Requirements

- VS Code 1.105+ or Cursor (same VSIX).
- The [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH.
- A SuperGrok / X Premium+ login, or an xAI API key. Grok's free tier does not include the CLI agent.

The extension never holds your key. Sign in with `grok login` or `XAI_API_KEY`.

## Use it in VS Code / Cursor

From this repo (`main`):

```bash
sbt --no-server host/packageVsix
code --install-extension beard/groks-beard.vsix --force
# Cursor: cursor --install-extension beard/groks-beard.vsix --force
```

Or iterate without packing:

```bash
sbt --no-server host/stageExtension
code --extensionDevelopmentPath=beard
```

Open chat with `Ctrl+;` / `Cmd+;`.

Keep/Undo snapshots persist in the editor (`storageUri`). Preview does not.

## Use it in the browser

Join Metals' sbt session, or start a private server if Metals is not the BSP:

```bash
sbt --no-server ~uiJS/ascentPreview
# if Metals is not the build server: sbt --server ~uiJS/ascentPreview
```

Open http://localhost:8765/ for live Grok. Canned chrome fixtures are `?scene=empty`, `slash`, `mentions`, `settings`, `transcript`, `permission`, `plan`, `question`, `elicit`, `changes`, `resume`.

Do not serve `target/` with a static file server. Preview restages on change and reloads over SSE.

## What you get

- Grok Build CLI only.
- TUI-shaped composer: `@path`, `@path:start-end`, slash from the live CLI, `/new`, `/resume`, `/model`, `/rename`, `/delete`.
- Ask-mode **review before write**: native multi-file diff, then Allow.
- Turn-grouped **Grok Changes**: Keep / Undo per file and per turn, including always-approve. Persist across reload in the editor.
- Opt-in TUI bridge: selection, reveal, path-based diffs. No writes through MCP.

Still missing versus the pager (see the internal `ROADMAP.md` gap audit): prompt history, `/copy` `/export`, `/effort`, todos, terminal handlers, rewind.

## Shortcuts

| Action | Key |
| --- | --- |
| Open Grok's Beard | `Ctrl+;` / `Cmd+;` |
| Add selection to chat | `Ctrl+Shift+;` / `Cmd+Shift+;` |
| Stop the running turn | `Escape` (chat focused) |

## Modules

| Path | Role |
| --- | --- |
| `beard/core` | Protocol, ChatRuntime, diffs, MCP tool dispatch (JVM + JS; `sbt testCore`) |
| `beard/ui` | Ascent chat webview |
| `beard/host` | VS Code / Cursor extension |
| `beard/mcp` | stdio MCP proxy for the external TUI |
| `beard/preview` | Live preview server (`LiveMain`) |

Tests: `sbt --no-server testCore` or `sbt --no-server verifyBeard`. The CLI owns tools, skills, MCP, memory, and compaction. The sidecar is path-only eyes (no writes).

## Copyright and license

Copyright [Russell White](https://github.com/russwyte).

Grok's Beard is an original work by Russell White, published as `early-effect.groks-beard`.

Licensed under the [Apache License, Version 2.0](LICENSE). You may not use this project except in compliance with the License.
