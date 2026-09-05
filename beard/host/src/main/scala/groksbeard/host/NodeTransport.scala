package groksbeard.host

import groksbeard.core.AcpTransport

import scala.scalajs.js

final class NodeTransport(child: ChildProcessHandle, log: String => Unit, onErr: String => Unit) extends AcpTransport:
  private var listener: String => Unit = _ => ()
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", (chunk: js.Any) => listener("" + chunk))
  child.stderr.on(
    "data",
    (chunk: js.Any) =>
      val line = "" + chunk
      log(line)
      onErr(line),
  )
  child.on("error", (err: js.Any) => log("grok spawn error: " + err))

  def onData(next: String => Unit): Unit = listener = next

  def write(data: String): Unit =
    val _ = child.stdin.write(data)

  def close(): Unit =
    child.stdin.end()
    val _ = child.kill()
end NodeTransport

object NodeTransport:
  def spawn(
      command: String,
      args: List[String],
      cwd: String,
      log: String => Unit,
      onErr: String => Unit = _ => (),
  ): NodeTransport =
    val child = nodeChildProcess.spawn(
      command,
      js.Array(args*),
      js.Dynamic.literal(cwd = cwd, stdio = js.Array("pipe", "pipe", "pipe")),
    )
    new NodeTransport(child, log, onErr)
  end spawn
end NodeTransport
