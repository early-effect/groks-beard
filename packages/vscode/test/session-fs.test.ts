import { expect, it } from "@effect/vitest"
import { type SessionFs, statsForSessionGroup } from "../src/session-fs.ts"

const memoryFs = (
  files: Record<string, { mtimeMs?: number; text?: string; dir?: boolean }>,
): SessionFs => ({
  list: (dir) =>
    Object.keys(files)
      .filter((path) => path.startsWith(`${dir}/`) && !path.slice(dir.length + 1).includes("/"))
      .map((path) => ({
        name: path.slice(dir.length + 1),
        isDirectory: files[path]?.dir === true,
      })),
  mtimeMs: (path) => files[path]?.mtimeMs,
  readText: (path) => files[path]?.text,
})

it("orders session dirs by updates.jsonl mtime", () => {
  const fs = memoryFs({
    "/sessions/old": { dir: true },
    "/sessions/new": { dir: true },
    "/sessions/old/updates.jsonl": { mtimeMs: 1 },
    "/sessions/new/updates.jsonl": { mtimeMs: 9 },
  })
  const ordered = statsForSessionGroup(fs, "/sessions")
  expect(ordered.map((row) => row.id)).toEqual(["new", "old"])
})
