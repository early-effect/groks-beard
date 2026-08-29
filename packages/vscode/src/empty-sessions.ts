export const EMPTY_SESSION_GRACE_MS = 10_000

export class EmptySessionTracker {
  readonly created = new Set<string>()
  readonly withHistory = new Set<string>()

  markCreated(sessionId: string): void {
    this.created.add(sessionId)
  }

  markHasHistory(sessionId: string): void {
    this.withHistory.add(sessionId)
  }

  shouldDelete(sessionId: string): boolean {
    return this.created.has(sessionId) && !this.withHistory.has(sessionId)
  }
}
