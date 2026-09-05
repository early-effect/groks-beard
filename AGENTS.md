# Agent notes (groks-beard)

## Pre-release: correctness and stellar UX

This is pre-release. Excellence and completeness come first. Value correctness and refactoring over a stable API or a frozen UI. Do not keep a HostMsg/WebviewMsg shape, a ChatRuntime method, or a layout when a cleaner design exists.

Read new code with a critical eye, especially functional ZIO design and UX. A passing test is not proof the shape is right. Listing a defect in this file does not make it shippable.

Priorities, in order:

1. **Correctness.** Match Grok Build (TUI + ACP) as the product. Measure the live CLI and the user guide. Do not ship a half analogue to spare a refactor.
2. **Design.** Prefer small pure functions and ZIO at effectful boundaries. `ChatRuntime` is a synchronized mutable bag because it grew that way, not because that is the target. Extract, do not pile on. Closed protocol unions may change in this repo; update tests and both sides of the contract in the same PR. Match HostMsg by type, not by positional arity, so adding a field is cheap.
3. **UX.** The bar is the Grok Build TUI: tight chrome, readable copy, no slop. Full-width menus and permission rows, mashed labels (`+2/-11 fileShow`), UUID session titles, composer-sized filters, toolbar crowding, and duplicate actions are defects. Fix them in the same change. Do not PR them as known leftover. A green Chekhov scene does not make ugly chrome shippable.
4. **Completeness.** Client-owned TUI commands (`/new`, `/resume`, `/model`, …) should work with and without arguments the way the pager does.

Not a priority: API stability, UI snapshot stability, "we already shipped this shape." A breaking change inside `beard/` is cheap. A muddled runtime is expensive. "We'll tidy the UI later" is not a reason to open the PR.

## Preview stays up until the human approves

`sbt --no-server ~uiJS/ascentPreview` (or `--server` when Metals is not the BSP) is the review surface. Do not stop LiveMain, do not commit, and do not open or update a PR until the human has looked at the running UI and said to commit or PR.

Playwright is not a substitute for that window.

## Visual review: click through, then hunt gutters

Chekhov passing is not visual review. A screenshot of the default scene is not visual review. Before asking for commit approval, and again before every PR that touches UI:

1. Open the live preview in a real Firefox window the human can see (`http://localhost:8765/` and the `?scene=` fixtures).
2. **Use the thing you changed.** Click it, type in it, open it, close it, pick a row, send, cancel. If you added a picker, open the picker. If you added a chip, add and remove it. A passing `?scene=` that never expands the new control does not count.
3. Measure, do not guess:

```js
({
  inner: [innerWidth, innerHeight],
  outer: [outerWidth, outerHeight],
  root: document.getElementById('root').getBoundingClientRect(),
})
```

   `#root` width/height must match `innerWidth`/`innerHeight` within a few pixels. If `innerWidth` disagrees with `outerWidth` by more than chrome (roughly 20px), Playwright has frozen a layout viewport inside the window. Resizing that window will do nothing. That is a miss. Never `setViewportSize` on a headed Firefox the human is watching. Review in a normal Firefox window (`open -a Firefox http://localhost:8765/`) and stretch *that* window.
4. html/body/#root share the app background. A transparent `html` plus a short `body` paints browser white.
5. Stretch the window. The composer stays on the bottom edge; unused height is the transcript/empty stage, not a void under Send.
6. Walk empty, slash, mentions, permission, plan, question, changes, resume. Click, type, send. Open the session picker and the model menu. If copy runs together, a menu spans the window, a title is a UUID, or Back leaves a transcript on `/`, the review failed. Fix it. Do not ask for a PR.

## Clean review artifacts before the PR

Screenshots, Playwright dumps, and other review temp files are not product. Before every commit that goes on a PR:

1. Delete root-level captures (`beard-*.png`, `page-*.png`, MCP `filename` screenshots). They are gitignored as `/*.png`. Product images live under `beard/media/`.
2. Do not `git add -A` from the repo root. That will still miss ignored pngs, but it will pick up other junk (`*.log` is ignored; untracked notes and worktrees are not).
3. `git status` must show no leftover review files. If an untracked png is listed, it is not ignored yet: add the pattern, delete the file, and only then commit.

Applies to: `beard/ui/**`, `beard/host/**`, `beard/preview/**`
