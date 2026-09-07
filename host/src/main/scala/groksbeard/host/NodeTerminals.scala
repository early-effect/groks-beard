package groksbeard.host

import groksbeard.core.*
import groksbeard.core.BeardError.orSystem
import zio.*

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
        js.Dynamic.literal(
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
    private val buf     = new StringBuilder
    private var cut     = false
    private var exit    = Option.empty[TerminalExitStatus]
    private var waiters = List.empty[TerminalExitStatus => Unit]

    def listen(): Unit =
      child.stdout.setEncoding("utf8")
      child.stderr.setEncoding("utf8")
      child.stdout.on("data", (chunk: js.Any) => append(chunk.toString))
      child.stderr.on("data", (chunk: js.Any) => append(chunk.toString))
      child.on(
        "exit",
        (code: js.Any) =>
          val n = code match
            case v if js.typeOf(v) == "number" => Some(v.asInstanceOf[Int])
            case _                             => None
          finish(TerminalExitStatus(n, None)),
      )
    end listen

    def append(chunk: String): Unit =
      synchronized {
        buf.append(chunk)
        val (kept, truncated) = Terminals.capTail(buf.toString, limit)
        if truncated then
          buf.clear()
          buf.append(kept)
          cut = true
      }

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
      val pending =
        synchronized {
          if exit.isDefined then Nil
          else
            exit = Some(st)
            val w = waiters
            waiters = Nil
            w
        }
      pending.foreach(_(st))
    end finish
  end Slot
end NodeTerminals
