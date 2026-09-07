package groksbeard.host

import groksbeard.core.*
import zio.*

import scala.scalajs.js

object NodeMcp:
  def awaitIn(cwd: String, post: HostMsg => UIO[Unit], log: String => Unit): UIO[Unit] =
    LocalMcp.awaitLocalHttp(
      read = ZIO.succeed(readMcpJson(cwd)),
      open = accepts,
      notify = msg => post(HostMsg.Error(msg)),
      log = line => ZIO.succeed(log(line)),
    )

  private def readMcpJson(cwd: String): Option[String] =
    val path = s"${cwd.replaceAll("[\\\\/]+$", "")}/.mcp.json"
    try Some(nodeFs.readFileSync(path, "utf8"))
    catch case _: Throwable => None

  private def accepts(url: String): UIO[Boolean] =
    LocalMcp.hostPort(url) match
      case None               => ZIO.succeed(false)
      case Some((host, port)) =>
        ZIO
          .async[Any, Nothing, Boolean] { cb =>
            val sock                      = nodeNet.connect(port, host)
            var done                      = false
            def finish(ok: Boolean): Unit =
              if !done then
                done = true
                try sock.destroy()
                catch case _: Throwable => ()
                cb(ZIO.succeed(ok))
            sock.on("connect", _ => finish(true))
            sock.on("error", _ => finish(false))
          }
          .timeout(LocalMcp.Attempt)
          .map(_.getOrElse(false))
end NodeMcp
