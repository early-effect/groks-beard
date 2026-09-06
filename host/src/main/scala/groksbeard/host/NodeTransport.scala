package groksbeard.host

import groksbeard.core.AcpTransport
import groksbeard.core.BeardError
import groksbeard.core.BeardError.orSystem
import zio.*

import scala.scalajs.js

final class NodeTransport(
    child: ChildProcessHandle,
    log: String => Unit,
    onErr: String => UIO[Unit],
    run: UIO[Any] => Unit,
) extends AcpTransport:
  private var ingest: String => UIO[Unit] = _ => ZIO.unit
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", (chunk: js.Any) => run(ingest("" + chunk)))
  child.stderr.on(
    "data",
    (chunk: js.Any) =>
      val line = "" + chunk
      log(line)
      if line.nonEmpty then run(onErr(line)),
  )
  child.on("error", (err: js.Any) => log("grok spawn error: " + err))

  def attach(next: String => UIO[Unit]): UIO[Unit] =
    ZIO.succeed { ingest = next }

  def write(data: String): BeardError.Result[Unit] =
    ZIO.attempt { val _ = child.stdin.write(data) }.orSystem

  def close: UIO[Unit] =
    ZIO.succeed {
      child.stdin.end()
      val _ = child.kill()
    }
end NodeTransport

object NodeTransport:
  def spawn(
      command: String,
      args: List[String],
      cwd: String,
      log: String => Unit,
      onErr: String => UIO[Unit] = _ => ZIO.unit,
      run: UIO[Any] => Unit,
  ): ZIO[Scope, BeardError, NodeTransport] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val child = nodeChildProcess.spawn(
          command,
          js.Array(args*),
          js.Dynamic.literal(cwd = cwd, stdio = js.Array("pipe", "pipe", "pipe")),
        )
        new NodeTransport(child, log, onErr, run)
      }.orSystem
    )(_.close)
end NodeTransport
