package groksbeard.ui

import zio.*
import zio.stream.ZStream

/** Coalesce a stream of events into one chunk per animation frame. */
object FrameBurst:
  val Window: Duration = 16.millis

  def apply[A](in: ZStream[Any, Nothing, A]): ZStream[Any, Nothing, Chunk[A]] =
    ZStream.unwrapScoped {
      for
        q <- Queue.unbounded[A]
        _ <- in.foreach(q.offer).forkScoped
      yield ZStream.repeatZIO {
        q.take.flatMap { first =>
          paint *> q.takeAll.map(rest => Chunk(first) ++ rest)
        }
      }
    }

  private def paint: UIO[Unit] =
    ZIO
      .asyncInterrupt[Any, Nothing, Unit] { cb =>
        try
          val id = ascent.dom.window.requestAnimationFrame((_: Double) => cb(ZIO.unit))
          Left(ZIO.succeed {
            ascent.dom.window.cancelAnimationFrame(id)
          })
        catch case _: Throwable => Right(ZIO.unit)
      }
      .timeout(Window)
      .unit
end FrameBurst
