package groksbeard.ui

import zio.*
import zio.stream.ZStream

import scala.scalajs.js

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
      .async[Any, Nothing, Unit] { cb =>
        try
          val w = js.Dynamic.global.window
          if js.typeOf(w.requestAnimationFrame) == "function" then
            val _ = w.requestAnimationFrame((_: Double) => cb(ZIO.unit))
          else cb(ZIO.unit)
        catch case _: Throwable => cb(ZIO.unit)
      }
      .timeout(Window)
      .unit
end FrameBurst
