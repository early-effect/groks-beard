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
            ProcessTransport.spawn(cmd, args, ".").map(t => (t.pid, t.isAlive))
          }
          (pid, wasAlive) = snap
          gone            = ProcessHandle.of(pid).filter(_.isAlive).isEmpty
        yield assertTrue(wasAlive, gone)
      }
    )
end ProcessTransportSpec
