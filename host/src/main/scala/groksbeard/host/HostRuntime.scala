package groksbeard.host

import groksbeard.core.BeardLog
import zio.*
import zio.stream.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object HostRuntime extends ZIOAppDefault:
  private val started                 = new AtomicBoolean(false)
  private val pending                 = new ConcurrentLinkedQueue[UIO[Any]]()
  private val unset: UIO[Any] => Unit = _ => ()
  private val sink                    = new AtomicReference[UIO[Any] => Unit](unset)
  private val scopeRef                = new AtomicReference[Option[Scope.Closeable]](None)
  private val writeLog                = new AtomicReference[String => Unit](line => java.lang.System.err.println(line))

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(BeardLog.logger(line => writeLog.get()(line)))

  def start(log: String => Unit): Unit =
    writeLog.set(log)
    if started.compareAndSet(false, true) then main(Array.empty)

  def start(): Unit = start(writeLog.get())

  def shutdown(): Unit =
    scopeRef.get().foreach { scope =>
      runUIO(scope.close(Exit.unit))
    }

  def runUIO(task: UIO[Any]): Unit =
    pending.add(task)
    val emit = sink.get()
    if emit ne unset then drain(emit)

  def runScoped[A](zio: ZIO[Scope, Nothing, A]): Unit =
    // `start` forks ZIOApp.main; activate keeps going. Look up the Scope when
    // this job runs, after `run` has set scopeRef, not at enqueue time.
    runUIO(
      ZIO.suspendSucceed {
        scopeRef.get() match
          case Some(scope) => scope.extend(zio)
          case None        => ZIO.dieMessage("HostRuntime not started")
      }
    )

  def run =
    Scope.make.flatMap { scope =>
      ZIO.succeed { scopeRef.set(Some(scope)) } *>
        ZIO.log("HostRuntime ready") *>
        ZStream
          .asyncZIO[Any, Nothing, UIO[Any]] { cb =>
            val emit: UIO[Any] => Unit = task => cb(ZIO.succeed(Chunk.single(task)))
            ZIO.succeed {
              sink.set(emit)
              drain(emit)
            }
          }
          .mapZIO(job => job.catchAllCause(c => ZIO.logErrorCause("HostRuntime job failed", c)))
          .runDrain
          .ensuring(ZIO.succeed { scopeRef.set(None) } *> scope.close(Exit.unit))
    }

  private def drain(emit: UIO[Any] => Unit): Unit =
    var job = pending.poll()
    while job != null do
      emit(job)
      job = pending.poll()
end HostRuntime
