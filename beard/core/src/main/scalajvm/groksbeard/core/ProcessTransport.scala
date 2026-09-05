package groksbeard.core

import zio.*
import zio.stream.*
import BeardError.orSystem

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

final class ProcessTransport(
    process: Process,
    listener: AtomicReference[String => UIO[Unit]],
    stdin: OutputStreamWriter,
) extends AcpTransport:
  def pid: Long = process.pid

  def isAlive: Boolean = process.isAlive

  def attach(ingest: String => UIO[Unit]): UIO[Unit] =
    ZIO.succeed { listener.set(ingest) }

  def write(data: String): BeardError.Result[Unit] =
    ZIO.attempt {
      stdin.synchronized {
        stdin.write(data)
        stdin.flush()
      }
    }.orSystem

  def close: UIO[Unit] = ProcessTransport.stop(process, Some(stdin)).ignore
end ProcessTransport

object ProcessTransport:
  def spawn(
      command: String,
      args: List[String],
      cwd: String,
      onErr: String => UIO[Unit] = _ => ZIO.unit,
  ): ZIO[Scope, BeardError, ProcessTransport] =
    for
      process <- ZIO.acquireRelease(
        ZIO.attempt {
          val pb = new ProcessBuilder((command :: args).asJava)
          pb.directory(new java.io.File(cwd))
          pb.redirectError(ProcessBuilder.Redirect.PIPE)
          pb.start()
        }.orSystem
      )(p => stop(p, None).ignore)
      listener <- ZIO.succeed(new AtomicReference[String => UIO[Unit]](_ => ZIO.unit))
      stdin    <- ZIO.acquireRelease(
        ZIO.attempt(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)).orSystem
      )(w => ZIO.succeed(closeQuietly(w)))
      _ <- ZStream
        .fromInputStream(process.getInputStream, 4096)
        .via(ZPipeline.utf8Decode)
        .foreach { chunk =>
          if chunk.nonEmpty then listener.get()(chunk) else ZIO.unit
        }
        .forkScoped
      _ <- ZStream
        .fromInputStream(process.getErrorStream, 4096)
        .via(ZPipeline.utf8Decode)
        .via(ZPipeline.splitLines)
        .foreach { line =>
          ZIO.succeed(java.lang.System.err.println(line)) *>
            (if line.nonEmpty then onErr(line) else ZIO.unit)
        }
        .forkScoped
    yield ProcessTransport(process, listener, stdin)

  private def stop(process: Process, stdin: Option[OutputStreamWriter]): BeardError.Result[Unit] =
    ZIO.attemptBlocking {
      stdin.foreach(closeQuietly)
      process.destroy()
      val _ = process.waitFor(2, TimeUnit.SECONDS)
      if process.isAlive then
        val _ = process.destroyForcibly()
        val _ = process.waitFor(1, TimeUnit.SECONDS)
    }.orSystem

  private def closeQuietly(stdin: OutputStreamWriter): Unit =
    try stdin.close()
    catch case _: Throwable => ()
end ProcessTransport
