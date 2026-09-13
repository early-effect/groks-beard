package groksbeard.core

import zio.test.*

object SessionPhaseSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionPhase")(
      test("Idle is not loading and has no pending resume") {
        val p = SessionPhase.Idle
        assertTrue(
          p.pendingResume.isEmpty,
          !p.loading,
          !p.metaLoading,
          !p.diskPainted,
          !p.paintDone,
          !p.attaching,
          !p.ignoreReplay,
        )
      },
      test("beginNew is Empty, ready, not loading") {
        val p = SessionPhase.beginNew
        assertTrue(
          p == SessionPhase.Empty,
          !p.loading,
          !p.metaLoading,
          p.pendingResume.isEmpty,
        )
      },
      test("beginResume waits on disk") {
        val p = SessionPhase.beginResume("sess")
        assertTrue(
          p == SessionPhase.ResumeDisk("sess"),
          p.pendingResume.contains("sess"),
          p.loading,
          p.metaLoading,
          !p.paintDone,
          p.loadCleared,
        )
      },
      test("onDisk with turns paints and stops the loading chrome") {
        val p = SessionPhase.onDisk(SessionPhase.beginResume("sess"), hasTurns = true)
        assertTrue(
          p == SessionPhase.ResumePainted("sess"),
          p.diskPainted,
          p.paintDone,
          !p.loading,
          p.metaLoading,
        )
      },
      test("onDisk without turns stays a resume wait") {
        val p = SessionPhase.onDisk(SessionPhase.beginResume("sess"), hasTurns = false)
        assertTrue(
          p == SessionPhase.ResumeEmpty("sess"),
          !p.diskPainted,
          p.paintDone,
          p.loading,
        )
      },
      test("startAttach after paint ignores load replay") {
        val painted = SessionPhase.onDisk(SessionPhase.beginResume("sess"), hasTurns = true)
        val p       = SessionPhase.startAttach(painted, useLoad = true)
        assertTrue(
          p == SessionPhase.Attaching("sess", Replay.Ignore, painted = true),
          p.attaching,
          p.ignoreReplay,
          p.diskPainted,
        )
      },
      test("startAttach after empty disk follows replay") {
        val empty = SessionPhase.onDisk(SessionPhase.beginResume("sess"), hasTurns = false)
        val p     = SessionPhase.startAttach(empty, useLoad = false)
        assertTrue(
          p == SessionPhase.Attaching("sess", Replay.Follow, painted = false),
          p.attaching,
          !p.ignoreReplay,
          p.loading,
        )
      },
      test("attached becomes Live") {
        val p = SessionPhase.attached(SessionPhase.beginResume("sess"), Some("sess"))
        assertTrue(p == SessionPhase.Live("sess"), p.pendingResume.isEmpty, !p.loading, !p.metaLoading)
      },
      test("cancel returns Empty and the abandoned id") {
        val (next, gone) = SessionPhase.cancel(SessionPhase.beginResume("sess"))
        assertTrue(next == SessionPhase.Empty, gone.contains("sess"))
      },
      test("fail is Empty") {
        assertTrue(SessionPhase.fail(SessionPhase.beginResume("sess")) == SessionPhase.Empty)
      },
      test("onDisk of Idle is a no-op") {
        assertTrue(SessionPhase.onDisk(SessionPhase.Idle, hasTurns = true) == SessionPhase.Idle)
      },
    )
end SessionPhaseSpec
