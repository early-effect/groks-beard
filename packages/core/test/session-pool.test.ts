import { expect, it } from "@effect/vitest"
import { computeDot, type PoolMember, selectReapable } from "../src/session-pool.ts"

const member = (overrides: Partial<PoolMember> & { sessionId: string }): PoolMember => ({
  focused: false,
  liveStatus: "idle",
  lastTouchedAt: 0,
  unread: false,
  unreadError: false,
  ...overrides,
})

it("computes dots from live status and unread flags", () => {
  expect(computeDot({ liveStatus: "working", unread: false, unreadError: false })).toBe("blue")
  expect(computeDot({ liveStatus: "needs-you", unread: false, unreadError: false })).toBe("yellow")
  expect(computeDot({ liveStatus: "idle", unread: false, unreadError: true })).toBe("red")
  expect(computeDot({ liveStatus: "idle", unread: true, unreadError: false })).toBe("green")
  expect(computeDot({ liveStatus: "idle", unread: false, unreadError: false })).toBe("gray")
})

it("never reaps focused, working, or needs-you sessions", () => {
  const now = 10_000_000
  const reaped = selectReapable(
    [
      member({ sessionId: "focus", focused: true, lastTouchedAt: 0 }),
      member({ sessionId: "work", liveStatus: "working", lastTouchedAt: 0 }),
      member({ sessionId: "ask", liveStatus: "needs-you", lastTouchedAt: 0 }),
      member({ sessionId: "stale", lastTouchedAt: 0 }),
    ],
    now,
    { idleTtlMs: 1000, lruCap: 8 },
  )
  expect(reaped).toEqual(["stale"])
})

it("reaps LRU idle sessions past the cap", () => {
  const now = 100
  const members = Array.from(
    { length: 10 },
    (_, i) => member({ sessionId: `s${i}`, lastTouchedAt: i }),
  )
  const reaped = selectReapable(members, now, { idleTtlMs: 1_000_000, lruCap: 8 })
  expect(reaped).toEqual(["s0", "s1"])
})
