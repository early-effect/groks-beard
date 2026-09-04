package groksbeard.core

import zio.*
import zio.stream.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

final class ProcessTransport(
    process: Process,
    listener: Ref[String => Unit],
    stdin: OutputStreamWriter,
) extends AcpTransport:
  def pid: Long = process.pid

  def isAlive: Boolean = process.isAlive

  def onData(next: String => Unit): Unit =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(listener.set(next)).getOrThrowFiberFailure()
    }

  def write(data: String): Unit =
    stdin.synchronized {
      stdin.write(data)
      stdin.flush()
    }

  def close(): Unit = ProcessTransport.stop(process, Some(stdin))
end ProcessTransport

object ProcessTransport:
  def spawn(command: String, args: List[String], cwd: String): ZIO[Scope, Throwable, ProcessTransport] =
    for
      process <- ZIO.acquireRelease(ZIO.attempt {
        val pb = new ProcessBuilder((command :: args).asJava)
        pb.directory(new java.io.File(cwd))
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.start()
      })(p => ZIO.succeed(stop(p, None)))
      listener <- Ref.make[String => Unit](_ => ())
      stdin    <- ZIO.acquireRelease(
        ZIO.succeed(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
      )(w => ZIO.succeed(closeQuietly(w)))
      _ <- ZStream
        .fromInputStream(process.getInputStream, 4096)
        .via(ZPipeline.utf8Decode)
        .foreach(chunk => listener.get.map(f => if chunk.nonEmpty then f(chunk)))
        .forkScoped
    yield ProcessTransport(process, listener, stdin)

  private def stop(process: Process, stdin: Option[OutputStreamWriter]): Unit =
    stdin.foreach(closeQuietly)
    process.destroy()
    val _ = process.waitFor(2, TimeUnit.SECONDS)
    if process.isAlive then
      val _ = process.destroyForcibly()
      val _ = process.waitFor(1, TimeUnit.SECONDS)

  private def closeQuietly(stdin: OutputStreamWriter): Unit =
    try stdin.close()
    catch case _: Throwable => ()
end ProcessTransport
