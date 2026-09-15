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
      test("a short last turn pads so the prompt can sit at the top") {
        val viewport = 200d
        val turnTop  = 800d
        val turnH    = 40d
        val content  = turnTop + turnH
        val pad      = TranscriptPageFlip.padPx(viewport, turnTop, content)
        val top      = TranscriptPageFlip.scrollTop(turnTop, turnH, viewport, content)
        assertTrue(pad == 160d, top == 800d)
      },
      test("a turn taller than the viewport follows the real tail") {
        val viewport = 200d
        val turnTop  = 800d
        val turnH    = 400d
        val content  = turnTop + turnH
        val pad      = TranscriptPageFlip.padPx(viewport, turnTop, content)
        val top      = TranscriptPageFlip.scrollTop(turnTop, turnH, viewport, content)
        assertTrue(pad == 0d, top == 1000d)
      },
    )
end TranscriptFollowSpec
