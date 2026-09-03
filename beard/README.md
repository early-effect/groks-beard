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

Do not serve `target/` with a static file server. Preview restages on change and reloads over SSE.

## Extension host (stub)

```bash
sbt --no-server host/stageExtension
code --extensionDevelopmentPath=beard
```
