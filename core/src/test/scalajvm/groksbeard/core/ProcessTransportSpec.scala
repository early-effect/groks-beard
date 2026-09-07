package groksbeard.core

import zio.*
import zio.test.*

object ProcessTransportSpec extends ZIOSpecDefault:
  def spec =
    suite("ProcessTransport")(
      test("scope exit destroys the child process") {
        val win  = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
        val cmd  = if win then "ping" else "sleep"
        val args = if win then List("-n", "20", "127.0.0.1") else List("20")
        for
          snap <- ZIO.scoped {
            ProcessTransport
              .spawn(cmd, args, ".")
              .mapError(e => new RuntimeException(e.message))
              .map(t => (t.pid, t.isAlive))
          }
          (pid, wasAlive) = snap
          gone            = ProcessHandle.of(pid).filter(_.isAlive).isEmpty
        yield assertTrue(wasAlive, gone)
        end for
      },
      test("onExit fires when the child exits on its own") {
        val win  = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
        val cmd  = if win then "cmd" else "true"
        val args = if win then List("/c", "exit", "0") else Nil
        ZIO.scoped {
          for
            fired <- Promise.make[Nothing, Int]
            _     <- ProcessTransport
              .spawn(cmd, args, ".", onExit = code => fired.succeed(code).unit)
              .mapError(e => new RuntimeException(e.message))
            code <- fired.await
          yield assertTrue(code == 0)
        }
      } @@ TestAspect.withLiveClock,
      test("onExit does not fire when the scope stops a live child") {
        val win  = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
        val cmd  = if win then "ping" else "sleep"
        val args = if win then List("-n", "20", "127.0.0.1") else List("20")
        for
          fired <- Ref.make(false)
          _     <- ZIO.scoped {
            ProcessTransport
              .spawn(cmd, args, ".", onExit = _ => fired.set(true))
              .mapError(e => new RuntimeException(e.message))
          }
          _   <- ZIO.sleep(200.millis)
          saw <- fired.get
        yield assertTrue(!saw)
        end for
      } @@ TestAspect.withLiveClock,
    )
end ProcessTransportSpec
