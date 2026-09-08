package groksbeard.host

import groksbeard.core.AcpTransport
import groksbeard.core.AgentLog
import groksbeard.core.BeardError
import groksbeard.core.BeardError.orSystem
import zio.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.scalajs.js

final class NodeTransport(
    child: ChildProcessHandle,
    log: String => Unit,
    onErr: String => UIO[Unit],
    onExit: Int => UIO[Unit],
    run: UIO[Any] => Unit,
    stopping: AtomicBoolean,
) extends AcpTransport:
  private var ingest: String => UIO[Unit] = _ => ZIO.unit
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", (chunk: js.Any) => run(ingest("" + chunk)))
  child.stderr.on(
    "data",
    (chunk: js.Any) =>
      val line = AgentLog.stripAnsi("" + chunk)
      log(line)
      if line.nonEmpty then run(onErr(line)),
  )
  child.on("error", (err: js.Any) => log("grok spawn error: " + err))
  child.on(
    "exit",
    (code: js.Any) =>
      if !stopping.get() then
        val parsed = groksbeard.facade.Browser.parseInt(code, 10)
        val n      = if parsed.isNaN then -1 else parsed.toInt
        run(onExit(n)),
  )

  def attach(next: String => UIO[Unit]): UIO[Unit] =
    ZIO.succeed { ingest = next }

  def write(data: String): BeardError.Result[Unit] =
    ZIO.attempt { val _ = child.stdin.write(data) }.orSystem

  def close: UIO[Unit] =
    ZIO.succeed {
      stopping.set(true)
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
      onExit: Int => UIO[Unit] = _ => ZIO.unit,
      run: UIO[Any] => Unit,
  ): ZIO[Scope, BeardError, NodeTransport] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val child = nodeChildProcess.spawn(
          command,
          js.Array(args*),
          SpawnOptions(cwd = cwd, stdio = js.Array("pipe", "pipe", "pipe")),
        )
        new NodeTransport(child, log, onErr, onExit, run, new AtomicBoolean(false))
      }.orSystem
    )(_.close)
end NodeTransport
