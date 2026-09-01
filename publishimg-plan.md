# Publishing Grok's Beard

Repo listing work (steps 1–3) is done on this branch: Apache-2.0 `LICENSE`, `packages/vscode` at `0.1.0` with repository / categories, a real marketplace README, and `pack.mjs` copies those into the VSIX. Do not `vsce publish` until the publisher login in step 4 is done.

Marketplace id is already decided: **`early-effect.groks-beard`**. Display name **Grok's Beard**. Repo: `https://github.com/early-effect/groks-beard`.

Two stores:

| Store | Who installs from it | Tool |
| --- | --- | --- |
| Visual Studio Marketplace | VS Code; Cursor can also install from it | `@vscode/vsce` |
| Open VSX | Cursor's gallery, VSCodium | `ovsx` |

Ship both so Cursor users do not need a VSIX file.

Keep the "not affiliated with SpaceXAI / xAI" line on the listing. Do not name the product "Grok Build".

---

## 1. Listing metadata (done)

`packages/vscode/package.json` (this is what `vsce` reads after pack rewrites `name` to `groks-beard`):

- Bumped `version` from `0.0.0` to **`0.1.0`** (first public).
- Added:
  - `"repository": { "type": "git", "url": "https://github.com/early-effect/groks-beard.git" }`
  - `"homepage": "https://github.com/early-effect/groks-beard"`
  - `"bugs": { "url": "https://github.com/early-effect/groks-beard/issues" }`
  - `"license": "Apache-2.0"`
  - `"categories": ["AI", "Chat", "Other"]`
  - `"keywords": ["grok", "grok build", "ai", "agent", "cursor"]`
- Left `publisher` as `early-effect`.
- Left `icon` as `media/logo.png`.
- Left `engines.vscode` as `^1.105.0`.
- Workspace package stays `private: true`; pack strips `private` on the staged copy.

Dedicated listing README is `packages/vscode/README.md` (install, requirements, shortcuts, disclaimer). Root README is the repo page; it still has the source install until the item is live (`ext install early-effect.groks-beard`).

---

## 2. License (done)

Apache-2.0 `LICENSE` at the repo root. Pack copies it into the VSIX (`extension/LICENSE.txt`). `"license": "Apache-2.0"` is set on the vscode package. Root README points at the file.

---

## 3. Fix `packages/vscode/scripts/pack.mjs` (done)

Pack copies the vscode listing README and root `LICENSE`, rewrites staged `name` to `groks-beard`, strips `private`, and keeps `--no-dependencies`. Skip flags are gone.

Sanity check after pack:

```bash
pnpm pack:vscode
unzip -l packages/vscode/groks-beard.vsix | grep -E 'README|LICENSE|package.json'
```

Open the staged README in the zip and confirm it is not the stub.

---

## 4. Publisher accounts (human, in the browser)

**VS Marketplace**

1. https://marketplace.visualstudio.com/manage
2. Create or confirm publisher id **`early-effect`** (must match `package.json` exactly).
3. Azure DevOps PAT with Marketplace **Manage** (or Acquire + Publish).
4. `npx @vscode/vsce login early-effect`

**Open VSX**

1. Sign in at https://open-vsx.org/ (Eclipse Foundation / GitHub).
2. Create a namespace `early-effect` if needed, or claim it.
3. Create an Open VSX PAT.
4. Do not reuse the Azure PAT here.

---

## 5. Publish `0.1.0`

Do not publish until step 3's zip looks right.

```bash
pnpm test
pnpm pack:vscode
npx @vscode/vsce publish --packagePath packages/vscode/groks-beard.vsix
npx ovsx publish packages/vscode/groks-beard.vsix -p "$OVSX_PAT"
```

Optional: git tag `v0.1.0` after the human merge, not before.

Store URL:

`https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard`

Install:

```text
ext install early-effect.groks-beard
```

---

## 6. After it is live

- Search **Grok's Beard** in VS Code and in Cursor.
- Install on a clean profile (not the dogfood `--force` VSIX) and confirm chat still starts against `grok agent stdio`.
- Point the GitHub repo About / README install section at the marketplace URL.

Later, not tomorrow: a tag-triggered workflow that packs and publishes. Manual `vsce publish` is enough for 0.1.0.

---

## Checklist

- [x] License file + `license` field
- [x] Version `0.1.0`
- [x] `repository` / `homepage` / `bugs`
- [x] Categories + keywords
- [x] Marketplace README (not the pack stub)
- [x] pack.mjs copies README + LICENSE; drop skip flags
- [x] Packed VSIX listing files look right
- [ ] Publisher `early-effect` exists on VS Marketplace
- [ ] `vsce login` + publish
- [ ] Open VSX namespace + `ovsx publish`
- [ ] Fresh-install smoke test
- [ ] README install instructions (root README, after the item is live)
