package groksbeard.core

/** Sticky autoscroll: follow the tail while the viewport is at the bottom. */
object TranscriptFollow:
  val SlackPx: Double = 32d

  def atTail(
      scrollTop: Double,
      scrollHeight: Double,
      clientHeight: Double,
      slack: Double = SlackPx,
  ): Boolean =
    val max = math.max(0d, scrollHeight - clientHeight)
    scrollTop >= max - slack
end TranscriptFollow
