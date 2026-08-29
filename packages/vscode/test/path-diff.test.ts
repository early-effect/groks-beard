import { expect, it } from "@effect/vitest"
import { NO_BEARD_SNAPSHOT_NOTICE, resolvePathDiff } from "../src/path-diff.ts"

it("prefers a Beard storageUri snapshot over git and disk", () => {
  const resolved = resolvePathDiff({
    path: "/repo/a.ts",
    beard: { original: "old", proposed: "new" },
    gitHead: "git-old",
    disk: "disk",
  })
  expect(resolved).toMatchObject({ ok: true, source: "beard", original: "old", proposed: "new" })
})

it("falls back to git HEAD then disk with a no-snapshot notice", () => {
  expect(resolvePathDiff({
    path: "/repo/a.ts",
    gitHead: "from-git",
    disk: "on-disk",
  })).toMatchObject({ ok: true, source: "git", original: "from-git", proposed: "on-disk" })
  expect(resolvePathDiff({
    path: "/repo/a.ts",
    disk: "on-disk",
  })).toMatchObject({
    ok: true,
    source: "disk",
    original: "on-disk",
    proposed: "on-disk",
    notice: NO_BEARD_SNAPSHOT_NOTICE,
  })
})

it("fails when the path cannot be resolved", () => {
  expect(resolvePathDiff({ path: "/missing.ts" })).toEqual({
    ok: false,
    reason: "file not found",
  })
})
