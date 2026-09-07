package groksbeard.core

import zio.*
import zio.stream.ZStream
import BeardError.orSystem

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

final class ProcessTerminals(defaultCwd: String) extends Terminals:
  private val ids   = TerminalSeq()
  private val lock  = new Object
  private var slots = Map.empty[TerminalId, ProcessTerminals.Slot]

  def create(
      command: String,
      args: List[String],
      cwd: Option[String],
      env: List[EnvVar],
      limit: Option[Int],
  ): BeardError.Result[TerminalId] =
    ZIO.attempt {
      val host = TerminalShell.hostShell()
      val pb   = new ProcessBuilder(Terminals.argv(command, args, host).asJava)
      pb.directory(new File(cwd.filter(_.nonEmpty).getOrElse(defaultCwd)))
      pb.redirectErrorStream(true)
      val map = pb.environment()
      TerminalShell.grokShellEnv(host).foreach(map.put("GROK_SHELL", _))
      env.foreach(v => map.put(v.name, v.value))
      val process = pb.start()
      val cap     = limit.filter(_ > 0).getOrElse(PlanTerminals.DefaultByteLimit)
      val slot    = ProcessTerminals.Slot(process, cap)
      slot.drain()
      lock.synchronized {
        val id = ids.next()
        slots += id -> slot
        id
      }
    }.orSystem

  def output(id: TerminalId): UIO[Option[TerminalOutputResult]] =
    ZIO.succeed {
      lock.synchronized(slots.get(id)).map(_.snapshot)
    }

  def stream(id: TerminalId): UIO[Option[ZStream[Any, Nothing, String]]] =
    ZIO.succeed(lock.synchronized(slots.get(id)).map(_.outputStream))

  def waitForExit(id: TerminalId): UIO[Option[TerminalExitStatus]] =
    lock.synchronized(slots.get(id)) match
      case None       => ZIO.none
      case Some(slot) =>
        ZIO.attemptBlocking(slot.awaitExit()).orDie.map(Some(_))

  def kill(id: TerminalId): UIO[Boolean] =
    ZIO.succeed {
      lock.synchronized(slots.get(id)) match
        case None       => false
        case Some(slot) =>
          slot.kill()
          true
    }

  def release(id: TerminalId): UIO[Boolean] =
    ZIO.succeed {
      lock.synchronized {
        slots.get(id) match
          case None       => false
          case Some(slot) =>
            slot.kill()
            slots -= id
            true
      }
    }

  def shutdown: UIO[Unit] =
    ZIO.succeed {
      lock.synchronized {
        slots.values.foreach(_.kill())
        slots = Map.empty
      }
    }
end ProcessTerminals

object ProcessTerminals:
  def layer(defaultCwd: String): ULayer[Terminals] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.succeed(ProcessTerminals(defaultCwd)))(_.shutdown)
    )

  private final class Slot(val process: Process, val limit: Int):
    private val buf       = new StringBuilder
    private var cut       = false
    private var exit      = Option.empty[TerminalExitStatus]
    private val exitLock  = new Object
    private var listeners = List.empty[String => Unit]
    private var completes = List.empty[() => Unit]

    def drain(): Unit =
      val t = new Thread(
        () =>
          val in    = process.getInputStream
          val bytes = Array.ofDim[Byte](4096)
          try
            var n = in.read(bytes)
            while n >= 0 do
              append(new String(bytes, 0, n, StandardCharsets.UTF_8))
              n = in.read(bytes)
          catch case _: Throwable => ()
          finally
            val code = process.waitFor()
            finish(TerminalExitStatus(Some(code), None))
          end try
        ,
        "beard-term",
      )
      t.setDaemon(true)
      t.start()
    end drain

    def outputStream: ZStream[Any, Nothing, String] =
      ZStream.asyncScoped { emit =>
        ZIO.succeed {
          synchronized {
            val prefix = buf.toString
            if prefix.nonEmpty then emit(ZIO.succeed(Chunk.single(prefix)))
            val push: String => Unit = s => emit(ZIO.succeed(Chunk.single(s)))
            val stop: () => Unit     = () => emit(ZIO.fail(None))
            listeners = push :: listeners
            completes = stop :: completes
            if exitLock.synchronized(exit.isDefined) then stop()
          }
        }
      }

    def append(chunk: String): Unit =
      val notify =
        synchronized {
          buf.append(chunk)
          val (kept, truncated) = Terminals.capTail(buf.toString, limit)
          if truncated then
            buf.clear()
            buf.append(kept)
            cut = true
          listeners
        }
      notify.foreach(_(chunk))
    end append

    def finish(st: TerminalExitStatus): Unit =
      val done =
        synchronized {
          exitLock.synchronized {
            if exit.isDefined then Nil
            else
              exit = Some(st)
              exitLock.notifyAll()
              val c = completes
              completes = Nil
              c
          }
        }
      done.foreach(_())
    end finish

    def snapshot: TerminalOutputResult =
      synchronized {
        TerminalOutputResult(buf.toString, cut, exitLock.synchronized(exit))
      }

    def awaitExit(): TerminalExitStatus =
      exitLock.synchronized {
        while exit.isEmpty do exitLock.wait()
        exit.get
      }

    def kill(): Unit =
      process.destroy()
      val _ = process.waitFor(2, TimeUnit.SECONDS)
      if process.isAlive then
        val _ = process.destroyForcibly()
        val _ = process.waitFor(1, TimeUnit.SECONDS)
      finish(TerminalExitStatus(None, Some("SIGTERM")))
    end kill
  end Slot
end ProcessTerminals
