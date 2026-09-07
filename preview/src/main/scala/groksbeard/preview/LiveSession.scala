package groksbeard.preview

import groksbeard.core.*
import groksbeard.core.BeardError.orSystem
import zio.*

import java.nio.file.{Files, Path as JPath}

final class LiveSession(
    val events: Hub[HostMsg],
    handle: Ref[WebviewMsg => UIO[Unit]],
    shutdown: UIO[Unit],
):
  def post(msg: WebviewMsg): UIO[Unit] =
    handle.get.flatMap(_(msg))

  def close: UIO[Unit] = shutdown

  def andThenClose(extra: UIO[Unit]): LiveSession =
    LiveSession(events, handle, shutdown *> extra)
end LiveSession

object LiveSession:
  def start(log: String => Unit): ZIO[Scope, Nothing, LiveSession] =
    val cwd = java.lang.System.getProperty("user.dir", ".")
    val env = (k: String) => Option(java.lang.System.getenv(k))
    val win = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    for
      events <- Hub.unbounded[HostMsg]
      handle <- Ref.make[WebviewMsg => UIO[Unit]](_ => ZIO.unit)
      emit    = (msg: HostMsg) => events.publish(msg).unit
      located = CliLocator.locate(LocateGrok(None, env, win, p => Files.isRegularFile(JPath.of(p))))
      close <- located match
        case Left(searched) =>
          val err = Onboarding.missingCliMessage(searched)
          log(err)
          handle
            .set {
              case WebviewMsg.Ready => emit(HostMsg.Ready) *> emit(HostMsg.Error(err))
              case _                => emit(HostMsg.Error(err))
            }
            .as(ZIO.unit)
        case Right(cmd) =>
          val args = Spawn.grokAgentStdioArgs()
          log(s"spawning $cmd ${args.mkString(" ")}")
          val note     = new java.util.concurrent.atomic.AtomicReference[String => UIO[Unit]](_ => ZIO.unit)
          val gone     = new java.util.concurrent.atomic.AtomicReference[UIO[Unit]](ZIO.unit)
          val home     = GrokHome(env)
          val caps     = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = true)
          val envLayer =
            HostOut.layer(emit) ++
              SessionRepo.of(NioSessionFs, home, cwd) ++
              Mentions.layer(q => ZIO.attemptBlocking(MentionWalk.fromDisk(cwd, q)).orSystem) ++
              ChangesPersist.noop ++
              TranscriptOut.of(NioSessionFs, home, cwd, env) ++
              ReviewOps.ignore ++
              ProcessTerminals.layer(cwd) ++
              ZLayer.succeed(
                Mcps.cli(
                  args => ProcessCapture.run(cmd, args, cwd),
                  ZIO.attempt(Files.readString(JPath.of(home, "config.toml"))).orElseSucceed(""),
                )
              )
          ProcessTransport
            .spawn(
              cmd,
              args,
              cwd,
              onErr = line => note.get()(line),
              onExit = _ => gone.get(),
            )
            .flatMap { transport =>
              ChatRuntime
                .make(
                  transport,
                  cwd,
                  caps,
                  includeActiveFile = () => true,
                  beforeInitialize = LocalMcp.awaitLocalHttp(
                    read = ZIO.attempt(Files.readString(JPath.of(cwd, ".mcp.json"))).option,
                    open = LiveSession.tcpOpen,
                    notify = msg => emit(HostMsg.Error(msg)),
                    log = line => ZIO.succeed(log(line)),
                  ),
                )
                .provideSome[Scope](envLayer)
                .flatMap { rt =>
                  note.set(rt.noteAgentLine)
                  gone.set(rt.noteAgentGone)
                  val shutdown =
                    ZIO.logInfo(s"closing grok agent (pid ${transport.pid})") *> rt.close
                  handle.set(msg => HostDispatch(rt, msg, emit)).as(shutdown)
                }
            }
            .catchAll { e =>
              handle
                .set {
                  case WebviewMsg.Ready => emit(HostMsg.Ready) *> emit(HostMsg.Error(e.message))
                  case _                => emit(HostMsg.Error(e.message))
                }
                .as(ZIO.unit)
            }
    yield LiveSession(events, handle, close)
    end for
  end start

  def fake(): ZIO[Scope, Nothing, LiveSession] =
    for
      events <- Hub.unbounded[HostMsg]
      handle <- Ref.make[WebviewMsg => UIO[Unit]](_ => ZIO.unit)
      emit = (msg: HostMsg) => events.publish(msg).unit
      rt <- ChatRuntime.make().provideSome[Scope](ChatEnv.test(post = emit))
      _  <- handle.set(msg => HostDispatch(rt, msg, emit))
    yield LiveSession(events, handle, rt.close)

  private def tcpOpen(url: String): UIO[Boolean] =
    LocalMcp.hostPort(url) match
      case None               => ZIO.succeed(false)
      case Some((host, port)) =>
        ZIO
          .attemptBlocking {
            val s = new java.net.Socket()
            try
              s.connect(new java.net.InetSocketAddress(host, port), LocalMcp.Attempt.toMillis.toInt)
              true
            finally s.close()
          }
          .orElseSucceed(false)
end LiveSession
