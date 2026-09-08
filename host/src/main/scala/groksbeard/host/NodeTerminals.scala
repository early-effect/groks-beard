package groksbeard.host

import groksbeard.core.*
import groksbeard.core.BeardError.orSystem
import zio.*
import zio.stream.ZStream

import scala.scalajs.js

final class NodeTerminals(defaultCwd: String) extends Terminals:
  private val ids   = TerminalSeq()
  private val lock  = new Object
  private var slots = Map.empty[TerminalId, NodeTerminals.Slot]

  def create(
      command: String,
      args: List[String],
      cwd: Option[String],
      env: List[EnvVar],
      limit: Option[Int],
  ): BeardError.Result[TerminalId] =
    ZIO.attempt {
      val host   = TerminalShell.hostShell()
      val argv   = Terminals.argv(command, args, host)
      val envMap = js.Dictionary.empty[String]
      nodeProcess.env.foreach { (k, v) =>
        v.toOption.foreach(envMap.update(k, _))
      }
      TerminalShell.grokShellEnv(host).foreach(envMap.update("GROK_SHELL", _))
      env.foreach(v => envMap.update(v.name, v.value))
      val child = nodeChildProcess.spawn(
        argv.head,
        js.Array(argv.drop(1)*),
        SpawnOptions(
          cwd = cwd.filter(_.nonEmpty).getOrElse(defaultCwd),
          env = envMap,
        ),
      )
      val cap  = limit.filter(_ > 0).getOrElse(PlanTerminals.DefaultByteLimit)
      val slot = NodeTerminals.Slot(child, cap)
      slot.listen()
      lock.synchronized {
        val id = ids.next()
        slots += id -> slot
        id
      }
    }.orSystem

  def output(id: TerminalId): UIO[Option[TerminalOutputResult]] =
    ZIO.succeed(lock.synchronized(slots.get(id)).map(_.snapshot))

  def stream(id: TerminalId): UIO[Option[ZStream[Any, Nothing, String]]] =
    ZIO.succeed(lock.synchronized(slots.get(id)).map(_.outputStream))

  def waitForExit(id: TerminalId): UIO[Option[TerminalExitStatus]] =
    lock.synchronized(slots.get(id)) match
      case None       => ZIO.none
      case Some(slot) => slot.awaitExit.map(Some(_))

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
end NodeTerminals

object NodeTerminals:
  def layer(defaultCwd: String): ULayer[Terminals] =
    ZLayer.scoped(ZIO.acquireRelease(ZIO.succeed(NodeTerminals(defaultCwd)))(_.shutdown))

  private final class Slot(child: ChildProcessHandle, limit: Int):
    private val buf       = new StringBuilder
    private var cut       = false
    private var exit      = Option.empty[TerminalExitStatus]
    private var waiters   = List.empty[TerminalExitStatus => Unit]
    private var listeners = List.empty[String => Unit]
    private var completes = List.empty[() => Unit]

    def listen(): Unit =
      child.stdout.setEncoding("utf8")
      child.stderr.setEncoding("utf8")
      child.stdout.on("data", (chunk: js.Any) => append(chunk.toString))
      child.stderr.on("data", (chunk: js.Any) => append(chunk.toString))
      child.on(
        "exit",
        (code: js.Any) =>
          val n =
            import groksbeard.facade.asInt
            code.asInt
          finish(TerminalExitStatus(n, None)),
      )
    end listen

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
            if exit.isDefined then stop()
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
          val cbs = listeners
          cbs
        }
      notify.foreach(_(chunk))
    end append

    def snapshot: TerminalOutputResult =
      synchronized {
        TerminalOutputResult(buf.toString, cut, exit)
      }

    def awaitExit: UIO[TerminalExitStatus] =
      ZIO.async { cb =>
        synchronized {
          exit match
            case Some(st) => cb(ZIO.succeed(st))
            case None     => waiters = (st => cb(ZIO.succeed(st))) :: waiters
        }
      }

    def kill(): Unit =
      val _ = child.kill()
      finish(TerminalExitStatus(None, Some("SIGTERM")))

    private def finish(st: TerminalExitStatus): Unit =
      val (pending, done) =
        synchronized {
          if exit.isDefined then (Nil, Nil)
          else
            exit = Some(st)
            val w = waiters
            val c = completes
            waiters = Nil
            completes = Nil
            (w, c)
        }
      pending.foreach(_(st))
      done.foreach(_())
    end finish
  end Slot
end NodeTerminals
