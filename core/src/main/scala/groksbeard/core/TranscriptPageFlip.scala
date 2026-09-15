package groksbeard.core

/** TUI page-flip: last user prompt at the top of the viewport, reply grows down.
  *
  * Bottom pad makes that pose a real scroll bottom. Without it, clamp jumps to the tail.
  */
object TranscriptPageFlip:
  val SlackPx: Double = TranscriptFollow.SlackPx

  def padPx(viewport: Double, turnTop: Double, contentWithoutPad: Double): Double =
    if viewport <= 0 then 0d
    else math.max(0d, turnTop + viewport - contentWithoutPad)

  def scrollTop(
      turnTop: Double,
      turnHeight: Double,
      viewport: Double,
      contentWithoutPad: Double,
  ): Double =
    val pad = padPx(viewport, turnTop, contentWithoutPad)
    val max = math.max(0d, contentWithoutPad + pad - viewport)
    if viewport > 0 && turnHeight >= viewport - SlackPx then max
    else math.min(math.max(0d, turnTop), max)
  end scrollTop
end TranscriptPageFlip
