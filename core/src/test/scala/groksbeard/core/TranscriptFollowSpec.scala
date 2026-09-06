package groksbeard.core

import zio.test.*

object TranscriptFollowSpec extends ZIOSpecDefault:
  def spec =
    suite("TranscriptFollow")(
      test("content shorter than the viewport is at the tail") {
        assertTrue(
          TranscriptFollow.atTail(0, 100, 200),
          TranscriptFollow.atTail(0, 200, 200),
        )
      },
      test("the last slack pixels still count as the tail") {
        assertTrue(
          TranscriptFollow.atTail(768, 1000, 200),
          TranscriptFollow.atTail(800, 1000, 200),
        )
      },
      test("scrolled up past slack is not at the tail") {
        assertTrue(!TranscriptFollow.atTail(0, 1000, 200), !TranscriptFollow.atTail(700, 1000, 200))
      },
    )
end TranscriptFollowSpec
