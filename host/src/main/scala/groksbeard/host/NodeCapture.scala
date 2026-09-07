package groksbeard.host

import groksbeard.core.*
import zio.*

import scala.scalajs.js

object NodeCapture:
  def run(command: String, args: List[String], cwd: String): BeardError.Result[String] =
    ZIO.async { cb =>
      try
        val child = nodeChildProcess.spawn(
          command,
          js.Array(args*),
          js.Dynamic.literal(cwd = cwd, stdio = js.Array("ignore", "pipe", "pipe")),
        )
        val out = StringBuilder()
        val err = StringBuilder()
        child.stdout.setEncoding("utf8")
        child.stderr.setEncoding("utf8")
        child.stdout.on(
          "data",
          (chunk: js.Any) =>
            val _ = out.append(chunk.toString)
            (),
        )
        child.stderr.on(
          "data",
          (chunk: js.Any) =>
            val _ = err.append(chunk.toString)
            (),
        )
        child.on(
          "error",
          (e: js.Any) => cb(ZIO.fail(BeardError.system(new RuntimeException(e.toString)))),
        )
        child.on(
          "close",
          (_: js.Any) =>
            val text = out.toString
            if text.nonEmpty then cb(ZIO.succeed(text))
            else
              val detail = err.toString.trim
              val msg    = if detail.nonEmpty then detail else s"$command produced no output"
              cb(ZIO.fail(BeardError.system(new RuntimeException(msg)))),
        )
      catch case e: Throwable => cb(ZIO.fail(BeardError.system(e)))
    }
end NodeCapture
