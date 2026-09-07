package groksbeard.core

import zio.*
import zio.test.*

object LocalMcpSpec extends ZIOSpecDefault:
  def spec =
    suite("LocalMcp")(
      test("keeps localhost HTTP urls and drops remote and stdio servers") {
        val raw =
          """{"mcpServers":{"metals":{"url":"http://localhost:56126/mcp","type":"http"},"atlassian":{"url":"https://mcp.atlassian.com/v1/mcp"},"playwright":{"command":"npx"}}}"""
        assertTrue(LocalMcp.localHttpUrls(raw) == List("http://localhost:56126/mcp"))
      },
      test("does not wait when there is no local HTTP MCP") {
        for
          notes <- Ref.make(Vector.empty[String])
          _     <- LocalMcp.awaitLocalHttp(
            read = ZIO.none,
            open = _ => ZIO.succeed(false),
            notify = s => notes.update(_ :+ s),
          )
          seen <- notes.get
        yield assertTrue(seen.isEmpty)
      },
      test("is silent when the port is already open") {
        for
          notes <- Ref.make(Vector.empty[String])
          _     <- LocalMcp.awaitLocalHttp(
            read = ZIO.some("""{"mcpServers":{"metals":{"url":"http://localhost:56126/mcp"}}}"""),
            open = _ => ZIO.succeed(true),
            notify = s => notes.update(_ :+ s),
          )
          seen <- notes.get
        yield assertTrue(seen.isEmpty)
      },
      test("retries until a local HTTP MCP accepts") {
        val url = "http://localhost:56126/mcp"
        for
          n     <- Ref.make(0)
          notes <- Ref.make(Vector.empty[String])
          logs  <- Ref.make(Vector.empty[String])
          fiber <- LocalMcp
            .awaitLocalHttp(
              read = ZIO.some("""{"mcpServers":{"metals":{"url":"http://localhost:56126/mcp"}}}"""),
              open = _ => n.updateAndGet(_ + 1).map(_ >= 3),
              notify = s => notes.update(_ :+ s),
              log = s => logs.update(_ :+ s),
            )
            .fork
          _    <- TestClock.adjust(5.seconds)
          _    <- fiber.join
          seen <- notes.get
          out  <- logs.get
        yield assertTrue(
          seen.head == LocalMcp.waitingNotice(url),
          seen.last.isEmpty,
          out.exists(_.contains("try 1")),
          out.exists(_.contains("is up")),
        )
        end for
      },
      test("tells the user when the wait times out") {
        val url = "http://localhost:56126/mcp"
        for
          notes <- Ref.make(Vector.empty[String])
          logs  <- Ref.make(Vector.empty[String])
          fiber <- LocalMcp
            .awaitLocalHttp(
              read = ZIO.some("""{"mcpServers":{"metals":{"url":"http://localhost:56126/mcp"}}}"""),
              open = _ => ZIO.succeed(false),
              notify = s => notes.update(_ :+ s),
              log = s => logs.update(_ :+ s),
            )
            .fork
          _    <- TestClock.adjust(LocalMcp.Limit + 10.seconds)
          _    <- fiber.join
          seen <- notes.get
          out  <- logs.get
        yield assertTrue(
          seen.head == LocalMcp.waitingNotice(url),
          seen.last == LocalMcp.timeoutNotice(Some(url)),
          out.exists(_.contains("try 1")),
          out.exists(_.contains("try 2")),
        )
        end for
      },
    )
end LocalMcpSpec
