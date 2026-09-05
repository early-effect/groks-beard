package groksbeard.ui

import zio.*
import zio.stream.ZStream

/** Coalesce a stream of events into one chunk per paint window. */
object FrameBurst:
  val Size: Int        = 128
  val Window: Duration = 16.millis

  def apply[A](in: ZStream[Any, Nothing, A]): ZStream[Any, Nothing, Chunk[A]] =
    in.groupedWithin(Size, Window)
end FrameBurst
