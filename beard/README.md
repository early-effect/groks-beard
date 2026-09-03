# beard

Greenfield Scala.js / Ascent rewrite of Grok's Beard. The TypeScript tree under `packages/` is frozen spec.

## Preview (agent design loop)

From the repo root, join Metals' BSP or fail:

```bash
sbt --no-server ~uiJS/ascentPreview
```

Then open:

- http://localhost:8765/?scene=empty
- http://localhost:8765/?scene=slash
- http://localhost:8765/?scene=mentions
- http://localhost:8765/?scene=settings
- http://localhost:8765/?scene=transcript
- http://localhost:8765/?scene=permission
- http://localhost:8765/?scene=plan
- http://localhost:8765/?scene=question
- http://localhost:8765/?scene=elicit

Do not serve `target/` with a static file server. Preview restages on change and reloads over SSE.

## UI tests (Chekhov / Firefox)

`ascent-chekhov` mounts the chat UI under ChekhovJSEnv (Playwright Firefox):

```bash
sbt --no-server "uiJS/chekhovInstall; uiJS/testFull"
```

## Extension host (fake ACP)

The host owns ACP. This slice talks to an in-process `FakeAgent` (no live `grok` spawn). Preview stays on `?scene=` fixtures.

```bash
sbt --no-server host/stageExtension
code --extensionDevelopmentPath=beard
```

Send in the sidebar should paint a user row, two thoughts, `hello`, an Edit tool group, and turn end.
