package groksbeard.host

import groksbeard.core.BeardError
import groksbeard.core.BeardError.orSystem
import groksbeard.core.SessionFs
import zio.*

import scala.scalajs.js

object NodeSessionFs extends SessionFs:
  def listNames(dir: String): BeardError.Result[List[String]] =
    ZIO.attempt {
      if nodeFs.existsSync(dir) && nodeFs.statSync(dir).isDirectory() then nodeFs.readdirSync(dir).toList
      else Nil
    }.orSystem

  def isDirectory(path: String): BeardError.Result[Boolean] =
    ZIO.attempt(nodeFs.existsSync(path) && nodeFs.statSync(path).isDirectory()).orSystem

  def mtimeMs(path: String): BeardError.Result[Option[Long]] =
    ZIO.attempt {
      if nodeFs.existsSync(path) then Some(nodeFs.statSync(path).mtimeMs.toLong) else None
    }.orSystem

  def readText(path: String): BeardError.Result[Option[String]] =
    ZIO.attempt {
      if nodeFs.existsSync(path) then Some(nodeFs.readFileSync(path, "utf8")) else None
    }.orSystem

  override def foldLines[S](path: String, z: S)(f: (S, String) => S): BeardError.Result[S] =
    ZIO.async { cb =>
      if !nodeFs.existsSync(path) then cb(ZIO.succeed(z))
      else
        try
          val stream = nodeFs.createReadStream(path, ReadStreamOptions(encoding = "utf8"))
          val rl     = nodeReadline.createInterface(ReadlineOptions(input = stream))
          var acc    = z
          var done   = false
          def finish(io: BeardError.Result[S]): Unit =
            if !done then
              done = true
              try rl.close()
              catch case _: Throwable => ()
              try stream.destroy()
              catch case _: Throwable => ()
              cb(io)
          rl.on(
            "line",
            line =>
              if !done then acc = f(acc, line.asInstanceOf[String]),
          )
          rl.on("close", _ => finish(ZIO.succeed(acc)))
          stream.on(
            "error",
            err => finish(ZIO.fail(BeardError.system(new RuntimeException(String.valueOf(err))))),
          )
        catch case t: Throwable => cb(ZIO.fail(BeardError.system(t)))
    }

  override def writeText(path: String, text: String): BeardError.Result[Unit] =
    ZIO.attempt {
      nodeFs.mkdirSync(nodePath.dirname(path), MkdirSyncOptions(recursive = true))
      nodeFs.writeFileSync(path, text, "utf8")
      ()
    }.orSystem

  override def deleteTree(path: String): BeardError.Result[Unit] =
    ZIO.attempt(nodeFs.rmSync(path, RmSyncOptions(recursive = true, force = true))).orSystem
end NodeSessionFs
