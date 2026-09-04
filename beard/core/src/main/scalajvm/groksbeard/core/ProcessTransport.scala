package groksbeard.core

import zio.*
import zio.stream.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

final class ProcessTransport(
    process: Process,
    listener: Ref[String => Unit],
    stdin: OutputStreamWriter,
) extends AcpTransport:
  def onData(next: String => Unit): Unit =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(listener.set(next)).getOrThrowFiberFailure()
    }

  def write(data: String): Unit =
    stdin.synchronized {
      stdin.write(data)
      stdin.flush()
    }

  def close(): Unit =
    try stdin.close()
    catch case _: Throwable => ()
    process.destroy()
end ProcessTransport

object ProcessTransport:
  def spawn(command: String, args: List[String], cwd: String): ZIO[Scope, Throwable, ProcessTransport] =
    for
      process <- ZIO.attempt {
        val pb = new ProcessBuilder((command :: args).asJava)
        pb.directory(new java.io.File(cwd))
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.start()
      }
      listener <- Ref.make[String => Unit](_ => ())
      stdin    <- ZIO.succeed(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
      _        <- ZStream
        .fromInputStream(process.getInputStream, 4096)
        .via(ZPipeline.utf8Decode)
        .foreach(chunk => listener.get.map(f => if chunk.nonEmpty then f(chunk)))
        .forkScoped
      _ <- ZIO.addFinalizer(ZIO.succeed {
        try stdin.close()
        catch case _: Throwable => (); process.destroy()
      })
    yield ProcessTransport(process, listener, stdin)
end ProcessTransport
