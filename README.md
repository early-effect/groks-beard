# Grok's Beard

VS Code / Cursor client for [Grok Build](https://x.ai). Named for the thing you grow while the agent works, and a nod to the prog-rock band [Spock's Beard](https://en.wikipedia.org/wiki/Spock%27s_Beard).

Created by [Russell White](https://github.com/russwyte). Published as **`early-effect.groks-beard`**.

> **Status: 0.2.0 (Scala).** Dogfood from source. The [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard) and [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard) listings are still the older 0.1 TypeScript build. Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

The product is Scala 3 at the repo root (Ascent, Scala.js, ZIO). Preview in the browser and the editor sidebar are the same UI.

| | **Editor chat** | **Browser preview** | **TUI sidecar** |
| --- | --- | --- | --- |
| **What** | VS Code / Cursor sidebar over `grok agent --leader stdio` | Same chat at http://localhost:8765/ | MCP tools the *external* Grok TUI can call |
| **Best when** | Daily use, Keep/Undo persist, native diffs | Iterate on chrome without packing a VSIX | Stay in Ghostty / iTerm and use the editor as eyes |

Shared identity is the CLI's session tree under `~/.grok/sessions/`. Start in the TUI, resume in the editor, and the reverse. Live sync (same backend, same stream) needs Grok's leader; see [Share a Grok leader](#share-a-grok-leader). The TUI is not hosted inside VS Code's terminal (xterm.js is a documented-bad host for Grok).

## Requirements

- VS Code 1.105+ or Cursor (same VSIX).
- The [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH.
- A SuperGrok / X Premium+ login, or an xAI API key. Grok's free tier does not include the CLI agent.

The extension never holds your key. Sign in with `grok login` or `XAI_API_KEY`.

## Use it in VS Code / Cursor

From this repo (`main`):

```bash
sbt --no-server host/packageVsix
code --install-extension groks-beard.vsix --force
# Cursor: cursor --install-extension groks-beard.vsix --force
```

Or iterate without packing:

```bash
sbt --no-server host/stageExtension
code --extensionDevelopmentPath=.
```

Open chat with `Ctrl+;` / `Cmd+;`.

Keep/Undo snapshots persist in the editor (`storageUri`). Preview does not.

## Use it in the browser

Join Metals' sbt session, or start a private server if Metals is not the BSP:

```bash
sbt --no-server ~uiJS/ascentPreview
# if Metals is not the build server: sbt --server ~uiJS/ascentPreview
```

Open http://localhost:8765/ for live Grok. Canned chrome fixtures are `?scene=empty`, `slash`, `mentions`, `settings`, `transcript`, `permission`, `plan`, `question`, `elicit`, `changes`, `resume`, `todos`, `tasks`, `palette`, `mcps`, `queue`, `session-info`, `context`, `child`, `cancel`, `agents`, `plan-view`, `workflows`, `dashboard`, `btw`, `theme`, `compact`, `doctor`, `voice`, `images`.

Do not serve `target/` with a static file server. Preview restages on change and reloads over SSE.

## Share a Grok leader

Beard is an ACP client. It always starts `grok agent stdio`. **Share Grok** (default on, `/settings` or `groksBeard.shareBackend`) passes `--leader`, so that process joins `~/.grok/leader.sock` instead of becoming a private backend. If no leader is running, Grok auto-starts `grok agent leader`. Share Grok off passes `--no-leader`.

That is all Beard needs in order to connect. An already-running TUI pager (`grok` without leader mode) is not a leader and does not hot-attach when Beard starts one. Beard does not write Grok's config.

To have an **active leader** the TUI also uses, set this yourself in `~/.grok/config.toml` (or `$GROK_HOME/config.toml`):

```toml
[cli]
use_leader = true
```

Then quit and restart `grok`. Check with `grok leader list`. You should see a reachable pid on `~/.grok/leader.sock`.

Order that works:

1. Enable `use_leader`, restart the TUI. It creates or joins the leader.
2. Open Beard or preview with Share Grok on. Spawn is `grok agent --leader stdio`. It joins the same sock.
3. Resume the same session in both. Live `session/update` is on that shared backend.

If Beard is up first, it may already have started a leader. Restarting the TUI after `use_leader = true` still joins that sock. Restarting the TUI **without** `use_leader` leaves the pager private; Beard stays on the leader and they will not stay in sync.

```bash
grok leader list          # reachable pid on ~/.grok/leader.sock
# Beard / preview log: spawning … grok agent --leader stdio
```

## What you get

- Grok Build CLI only.
- TUI-shaped composer: `@path`, `@path:start-end`, slash from the live CLI, `/new`, `/resume`, `/model`, `/effort`, `/rename`, `/delete`, `/history`, `/copy`, `/export`, `/rewind` (`/undo`), `/fork`, `/mcps`, `/session-info` (`/status`, `/info`), `/context`.
- Command palette (`Ctrl+P` / `?` in chat): session actions plus MCP servers. Occupancy meter click opens `/context`.
- Mid-turn queue pane: Send now, Edit, Drop. `Ctrl+4` toggles it. Empty Enter sends the top follow-up now.
- Ask-mode **review before write**: native multi-file diff, then Allow.
- Follow-along: the transcript shows the file the agent is in. Click it to open; the editor does not jump on its own.
- Agent markdown: headings, nested lists, `+` bullets, fences, quote paragraphs, and GFM tables (with or without a leading `|`). `javascript:` stays text. `https` links open in a new tab. Plan cards use the same renderer.
- Todos (`Ctrl+T`) and tasks (`Ctrl+G`): the pane opens while work is live, Hide dismisses it, and the shortcut reopens history. A still-running task line stays above the composer.
- Subagents: compact lifecycle row, tasks group, child-transcript attach, kill, cancel-turn 1–4, limited steer composer.
- `/config-agents`, `/view-plan`, stash (`Ctrl+S`, idle Esc Esc), `/btw`, `/dashboard`, `/workflow runs`, `/theme`, `/compact-mode` / `/minimal`, `/vim-mode`, `/doctor`, `/voice`.
- ACP `session/set_config_option`, `embeddedContext` resource blocks, image chips (image block or resource blob), `session/list` for the dashboard.
- Turn-grouped **Grok Changes**: Keep / Undo per file and per turn, including always-approve. Persist across reload in the editor.
- Opt-in TUI bridge: selection, reveal, path-based diffs. No writes through MCP.

`/settings`, `/theme`, `/vim-mode`, `/compact-mode`, and Always-stop write `$GROK_HOME/config.toml` only when you change them. Image paste attaches a chip even when `promptCapabilities.image` is false (sent as a resource blob). TTY-only doctor probes read as not applicable.

## Shortcuts

| Action | Key |
| --- | --- |
| Open Grok's Beard | `Ctrl+;` / `Cmd+;` |
| Add selection to chat | `Ctrl+Shift+;` / `Cmd+Shift+;` |
| Toggle todos | `Ctrl+T` (chat focused) |
| Toggle tasks | `Ctrl+G` (chat focused) |
| Toggle queue | `Ctrl+4` (chat focused, when something is queued) |
| Command palette | `Ctrl+P` / `Cmd+P` (chat focused), or `?` on an empty prompt |
| Stop the running turn | `Ctrl+C` on an empty draft, or Stop. Esc never cancels. |
| Stash / restore draft | `Ctrl+S` / `Alt+S` |
| Clear draft (stash) | idle `Esc Esc` within 800ms |
| Rewind (idle, empty prompt) | `Esc Esc` within 800ms |
| Dashboard | `Ctrl+\` |
| Always-approve | `Ctrl+O` |
| Voice | `Ctrl+Space` / `/voice` |
| Follow the tail | Click ↓, `End`, or `Ctrl+End`. Vim `G`. |

## Modules

| Path | Role |
| --- | --- |
| `core` | Protocol, ChatRuntime, diffs, MCP tool dispatch (JVM + JS; `sbt testCore`) |
| `ui` | Ascent chat webview |
| `host` | VS Code / Cursor extension |
| `mcp` | stdio MCP proxy for the external TUI |
| `preview` | Live preview server (`LiveMain`) |
| `facade` | VS Code webview API facade |

`core` is a projectMatrix. `core/testFull` is the JVM axis only; `testCore` is `core/testFull; coreJS/testFull`. Match CI (Chekhov + every module + splice) with `sbt --no-server verifyBeard`. UI tests: `sbt --no-server "uiJS/chekhovInstall; uiJS/testFull"`. The CLI owns tools, skills, MCP, memory, and compaction. The sidecar is path-only eyes (no writes). The TUI proxy is `dist/mcp-proxy.js` (Node, not `process.execPath`).

## Copyright and license

Copyright [Russell White](https://github.com/russwyte).

Grok's Beard is an original work by Russell White, published as `early-effect.groks-beard`.

Licensed under the [Apache License, Version 2.0](LICENSE). You may not use this project except in compliance with the License.
