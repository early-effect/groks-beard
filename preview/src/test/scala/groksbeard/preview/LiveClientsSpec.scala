package groksbeard.preview

import groksbeard.core.*
import zio.*
import zio.test.*

object LiveClientsSpec extends ZIOSpecDefault:
  def spec =
    suite("LiveClients")(
      test("validId accepts uuid-shaped tokens and rejects empty") {
        assertTrue(
          LiveClients.validId("3f2a0c2e-7b1d-4c8a-9f11-0ab3d4e5f678"),
          LiveClients.validId("tab_1"),
          !LiveClients.validId(""),
          !LiveClients.validId("a/b"),
          !LiveClients.validId("x" * (LiveClients.MaxId + 1)),
        )
      },
      test("a send on one client does not appear on another") {
        for
          clients <- LiveClients.fake()
          fromB   <- clients.eventStream("b").interruptAfter(800.millis).runCollect.fork
          _       <- ZIO.sleep(80.millis)
          _       <- clients.post("b", WebviewMsg.Ready)
          _       <- clients.post("a", WebviewMsg.Ready)
          _       <- clients.post("a", WebviewMsg.Send("only-a"))
          b       <- fromB.join
          texts = b.collect { case HostMsg.UserMessage(_, text, _, _) => text }
        yield assertTrue(!texts.contains("only-a"))
      } @@ TestAspect.withLiveClock,
      test("the addressed client still sees its own send") {
        for
          clients <- LiveClients.fake()
          fromA   <- clients
            .eventStream("a")
            .filter {
              case HostMsg.UserMessage(_, "only-a", _, _) => true
              case _                                      => false
            }
            .take(1)
            .interruptAfter(2.seconds)
            .runCollect
            .fork
          _ <- ZIO.sleep(80.millis)
          _ <- clients.post("a", WebviewMsg.Ready)
          _ <- clients.post("a", WebviewMsg.Send("only-a"))
          a <- fromA.join
        yield assertTrue(a.nonEmpty)
      } @@ TestAspect.withLiveClock,
      test("eventStream delivers HostMsg while Ready is still running") {
        for
          started <- Promise.make[Nothing, Unit]
          hold    <- Promise.make[Nothing, Unit]
          events  <- Hub.unbounded[HostMsg]
          handle  <- Ref.make[WebviewMsg => UIO[Unit]](_ => ZIO.unit)
          _       <- handle.set {
            case WebviewMsg.Ready =>
              started.succeed(()) *> hold.await *> events.publish(HostMsg.Ready).unit
            case _ => ZIO.unit
          }
          session = LiveSession(events, handle, ZIO.unit)
          clients <- LiveClients.make(ZIO.succeed(session))
          got     <- clients
            .eventStream("c")
            .filter {
              case HostMsg.Error("waiting", _) => true
              case _                           => false
            }
            .take(1)
            .runCollect
            .fork
          _    <- started.await
          _    <- events.publish(HostMsg.Error("waiting"))
          seen <- got.join
          _    <- hold.succeed(())
        yield assertTrue(seen.nonEmpty)
      } @@ TestAspect.timeout(5.seconds),
    )
end LiveClientsSpec
