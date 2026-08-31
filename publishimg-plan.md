# Publishing Grok's Beard

Attack this in order. Do not `vsce publish` from the current pack: the store page would show version `0.0.0`, no license, and a stub README that pack.mjs writes as "Local VSIX…".

Marketplace id is already decided: **`early-effect.groks-beard`**. Display name **Grok's Beard**. Repo: `https://github.com/early-effect/groks-beard`.

Two stores:

| Store | Who installs from it | Tool |
| --- | --- | --- |
| Visual Studio Marketplace | VS Code; Cursor can also install from it | `@vscode/vsce` |
| Open VSX | Cursor's gallery, VSCodium | `ovsx` |

Ship both so Cursor users do not need a VSIX file.

Keep the "not affiliated with SpaceXAI / xAI" line on the listing. Do not name the product "Grok Build".

---

## 1. Listing metadata

Edit `packages/vscode/package.json` (this is what `vsce` reads after pack rewrites `name` to `groks-beard`):

- Bump `version` from `0.0.0` to **`0.1.0`** (first public).
- Add:
  - `"repository": { "type": "git", "url": "https://github.com/early-effect/groks-beard.git" }`
  - `"homepage": "https://github.com/early-effect/groks-beard"`
  - `"bugs": { "url": "https://github.com/early-effect/groks-beard/issues" }`
  - `"license": "Apache-2.0"` (or whatever you pick in step 2)
  - `"categories": ["AI", "Chat", "Other"]`
  - `"keywords": ["grok", "grok build", "ai", "agent", "cursor"]`
- Leave `publisher` as `early-effect`.
- Leave `icon` as `media/logo.png`.
- Leave `engines.vscode` as `^1.105.0`.
- `private: true` is fine for the workspace package; pack already copies a staged `package.json`. If vsce still complains, strip `private` in the staged copy only.

Root `README.md` is the listing copy. `packages/vscode/README.md` is a stub. Either replace the vscode README with a marketplace-focused page (install, requirements, shortcuts, disclaimer) or have pack copy the root README. Prefer a dedicated listing README under `packages/vscode/README.md` so the store does not dump the modules table.

Update the root README install section once the item is live (`ext install early-effect.groks-beard`).

---

## 2. License

Root README still says TBD. Marketplace requires a license file.

- Add `LICENSE` at the repo root (Apache-2.0 matches the early-effect org unless you want something else).
- Copy it into the VSIX stage (see pack.mjs).
- Set `"license"` in `packages/vscode/package.json` to match.

---

## 3. Fix `packages/vscode/scripts/pack.mjs`

Today it:

- Overwrites staged `README.md` with two lines of "Local VSIX" copy.
- Passes `--allow-missing-repository` and `--skip-license`.

Change it so the staged VSIX includes:

- Real README (vscode listing README, or root README).
- `LICENSE`.
- Staged `package.json` with `name: "groks-beard"` plus repository / license / categories.

Drop `--allow-missing-repository` and `--skip-license` once those fields exist. Keep `--no-dependencies` (the extension is already bundled).

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

- [ ] License file + `license` field
- [ ] Version `0.1.0`
- [ ] `repository` / `homepage` / `bugs`
- [ ] Categories + keywords
- [ ] Marketplace README (not the pack stub)
- [ ] pack.mjs copies README + LICENSE; drop skip flags
- [ ] Packed VSIX listing files look right
- [ ] Publisher `early-effect` exists on VS Marketplace
- [ ] `vsce login` + publish
- [ ] Open VSX namespace + `ovsx publish`
- [ ] Fresh-install smoke test
- [ ] README install instructions
