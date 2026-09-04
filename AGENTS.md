# Agent notes (groks-beard)

## Preview stays up until the human approves

`sbt --no-server ~uiJS/ascentPreview` (or `--server` when Metals is not the BSP) is the review surface. Do not stop LiveMain, do not commit, and do not open or update a PR until the human has looked at the running UI and said to commit or PR.

Playwright is not a substitute for that window.

## Visual review: fill the window, hunt gutters

Chekhov passing is not visual review. Before asking for commit approval:

1. Open the live preview in a real Firefox window the human can see (`http://localhost:8765/` and the `?scene=` fixtures).
2. Measure, do not guess:

```js
({
  inner: [innerWidth, innerHeight],
  outer: [outerWidth, outerHeight],
  root: document.getElementById('root').getBoundingClientRect(),
})
```

   `#root` width/height must match `innerWidth`/`innerHeight` within a few pixels. If `innerWidth` disagrees with `outerWidth` by more than chrome (roughly 20px), Playwright has frozen a layout viewport inside the window. Resizing that window will do nothing. That is a miss. Never `setViewportSize` on a headed Firefox the human is watching. Review in a normal Firefox window (`open -a Firefox http://localhost:8765/`) and stretch *that* window.
3. html/body/#root share the app background. A transparent `html` plus a short `body` paints browser white.
4. Stretch the window. The composer stays on the bottom edge; unused height is the transcript/empty stage, not a void under Send.
5. Walk empty, slash, mentions, permission, plan, question, changes. Click, type, send. Look for clipped chips, full-width accidents, ghost buttons, occupancy crowding the toolbar.

## Clean review artifacts before the PR

Screenshots, Playwright dumps, and other review temp files are not product. Before every commit that goes on a PR:

1. Delete root-level captures (`beard-*.png`, `page-*.png`, MCP `filename` screenshots). They are gitignored as `/*.png`. Product images live under `beard/media/`.
2. Do not `git add -A` from the repo root. That will still miss ignored pngs, but it will pick up other junk (`*.log` is ignored; untracked notes and worktrees are not).
3. `git status` must show no leftover review files. If an untracked png is listed, it is not ignored yet: add the pattern, delete the file, and only then commit.

Applies to: `beard/ui/**`, `beard/host/**`, `beard/preview/**`
