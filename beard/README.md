# beard

Greenfield Scala.js / Ascent rewrite of Grok's Beard. The TypeScript tree under `packages/` is frozen spec.

## Preview (agent design loop)

Datastar-example shape: stage the UI, then run the JVM server that composes `Preview.routes` with live Grok.

```bash
sbt --no-server ~uiJS/ascentPreview   # splice + stamp (no server)
sbt --no-server preview/run           # :8765, Preview.routes ++ grok ACP
```

Open http://localhost:8765/ for **live Grok** (`grok agent stdio`, not FakeAgent). Canned scenes stay on `?scene=`:

- http://localhost:8765/?scene=empty
- http://localhost:8765/?scene=slash
- http://localhost:8765/?scene=mentions
- http://localhost:8765/?scene=settings
- http://localhost:8765/?scene=transcript
- http://localhost:8765/?scene=permission
- http://localhost:8765/?scene=plan
- http://localhost:8765/?scene=question
- http://localhost:8765/?scene=elicit
- http://localhost:8765/?scene=changes

Do not serve `target/` with a static file server. Preview restages on change and reloads over SSE.

## Core tests (JVM + JS)

`beard/core` is a projectMatrix. `core/testFull` is the JVM axis only. CI's zipx `test` job runs aggregate `testFull`, which links and runs `coreJS`. Scala.js rejects `java.security.MessageDigest` at link time and `(?m)` at runtime.

```bash
sbt --no-server testCore
```

That alias is `core/testFull; coreJS/testFull`. Match CI (Chekhov + every module + splice):

```bash
sbt --no-server verifyBeard
```

## UI tests (Chekhov / Firefox)

`ascent-chekhov` mounts the chat UI under ChekhovJSEnv (Playwright Firefox):

```bash
sbt --no-server "uiJS/chekhovInstall; uiJS/testFull"
```

## Extension host

The host and `preview/run` spawn `grok agent stdio` (no `--always-approve` / `--yolo` / `--no-leader`, no `terminal: true`). Unit tests keep FakeAgent. Canned `?scene=` fixtures stay for Chekhov.

```bash
sbt --no-server host/stageExtension
code --extensionDevelopmentPath=beard
```

Send in the sidebar should paint a user row, two thoughts, `hello`, an Edit tool with `+N/-M`, and a Grok Changes panel. Open/Review shows a sidebar diff; Keep drops the file. The host also opens native `vscode.changes` (pairwise `vscode.diff` if that command is missing).

### TUI bridge

Command **Grok's Beard: Enable TUI Bridge** binds a per-workspace socket and offers Write or Copy for project `.grok/config.toml`. It never writes `~/.grok/config.toml`. The proxy is `beard/dist/mcp-proxy.js` (Node, not `process.execPath`). After write, press `r` in a running TUI `/mcps`. Disable unbinds the socket.

### VSIX

```bash
sbt --no-server host/packageVsix
code --install-extension beard/groks-beard.vsix --force
```
