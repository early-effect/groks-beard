package groksbeard.preview

import groksbeard.core.*
import zio.*

import java.nio.file.{Files, Path as JPath}

final class LiveSession(
    val events: Hub[HostMsg],
    handle: Ref[WebviewMsg => Unit],
    shutdown: UIO[Unit],
):
  def post(msg: WebviewMsg): UIO[Unit] =
    handle.get.map(_(msg))

  def close: UIO[Unit] = shutdown

  def andThenClose(extra: UIO[Unit]): LiveSession =
    LiveSession(events, handle, shutdown *> extra)
end LiveSession

object LiveSession:
  def managed(log: String => Unit): UIO[LiveSession] =
    Scope.make.flatMap { scope =>
      scope.extend(start(log)).map(_.andThenClose(scope.close(Exit.unit)))
    }

  def fake(): UIO[LiveSession] =
    for
      events <- Hub.unbounded[HostMsg]
      handle <- Ref.make[WebviewMsg => Unit](_ => ())
      emit = publish(events)
      rt   = ChatRuntime(emit)
      _ <- handle.set(msg => HostDispatch(rt, msg, emit))
    yield LiveSession(events, handle, ZIO.succeed(rt.close()))

  def start(log: String => Unit): ZIO[Scope, Nothing, LiveSession] =
    val cwd = java.lang.System.getProperty("user.dir", ".")
    val env = (k: String) => Option(java.lang.System.getenv(k))
    val win = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    for
      events <- Hub.unbounded[HostMsg]
      handle <- Ref.make[WebviewMsg => Unit](_ => ())
      emit    = publish(events)
      located = CliLocator.locate(LocateGrok(None, env, win, p => Files.isRegularFile(JPath.of(p))))
      close <- located match
        case Left(searched) =>
          val err = Onboarding.missingCliMessage(searched)
          log(err)
          handle
            .set {
              case WebviewMsg.Ready =>
                emit(HostMsg.Ready)
                emit(HostMsg.Error(err))
              case _ => emit(HostMsg.Error(err))
            }
            .as(ZIO.unit)
        case Right(cmd) =>
          val args = Spawn.grokAgentStdioArgs()
          log(s"spawning $cmd ${args.mkString(" ")}")
          val note = new java.util.concurrent.atomic.AtomicReference[String => Unit](_ => ())
          ProcessTransport.spawn(cmd, args, cwd, onErr = line => note.get()(line)).orDie.flatMap { transport =>
            val caps = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = false)
            val home = GrokHome(env)
            val rt   = ChatRuntime(
              emit,
              transport,
              cwd = cwd,
              capabilities = caps,
              searchFiles = q => MentionWalk.fromDisk(cwd, q),
              includeActiveFile = () => true,
              listSessions = () => SessionWalk.fromDisk(home, cwd),
              scheduleEmptyDelete = id =>
                val path = SessionIndex.sessionPath(home, cwd, id)
                val t    = new Thread(() =>
                  Thread.sleep(SessionIndex.EmptyGraceMs)
                  NioSessionFs.deleteTree(path)
                )
                t.setDaemon(true)
                t.start(),
            )
            note.set(rt.noteAgentLine)
            val shutdown = ZIO.succeed {
              log(s"closing grok agent (pid ${transport.pid})")
              rt.close()
            }
            ZIO.addFinalizer(shutdown) *> handle.set(msg => HostDispatch(rt, msg, emit)).as(shutdown)
          }
    yield LiveSession(events, handle, close)
    end for
  end start

  private def publish(events: Hub[HostMsg]): HostMsg => Unit =
    (msg: HostMsg) =>
      Unsafe.unsafe { implicit u =>
        val _ = Runtime.default.unsafe.run(events.publish(msg)).getOrThrowFiberFailure()
      }
end LiveSession
