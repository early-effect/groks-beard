# Grok's Beard

A VS Code / Cursor client for [Grok Build](https://x.ai). Named for the thing you grow while the agent works, and a nod to [Spock's Beard](https://en.wikipedia.org/wiki/Spock%27s_Beard).

Created by [Russell White](https://github.com/russwyte). Published as **`early-effect.groks-beard`**.

Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

## Install

You need:

- VS Code 1.105+ or Cursor
- The [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH
- A SuperGrok / X Premium+ login, or an xAI API key. Grok's free tier does not include the CLI agent.

Sign in with `grok login` or `XAI_API_KEY`. The extension never holds your key.

The [Marketplace](https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard) and [Open VSX](https://open-vsx.org/extension/early-effect/groks-beard) listings are still an older TypeScript build. Install from this repo:

```bash
sbt --no-server host/packageVsix
code --install-extension groks-beard.vsix --force
# Cursor: cursor --install-extension groks-beard.vsix --force
```

Reload the window. Open chat with `Ctrl+;` / `Cmd+;`. Add the current selection with `Ctrl+Shift+;` / `Cmd+Shift+;`.

## Use it with the Grok TUI

Sessions live under `~/.grok/sessions/`. Start in the TUI and resume here, or the reverse.

Live updates in both at once need Grok's leader. Share Grok is on by default (`/settings`). In the TUI, set this and restart `grok`:

```toml
[cli]
use_leader = true
```

`grok leader list` should show a reachable pid on `~/.grok/leader.sock`.

## Copyright and license

Copyright [Russell White](https://github.com/russwyte).

Grok's Beard is an original work by Russell White, published as `early-effect.groks-beard`.

Licensed under the [Apache License, Version 2.0](LICENSE).
