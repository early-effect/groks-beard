package groksbeard.preview

import ascent.preview.{Preview, PreviewConfig}
import groksbeard.core.*
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.*

import java.nio.file.{Files, Path as JPath}

/** Same-origin preview + live `grok agent stdio`. Datastar-example shape: [[Preview.routes]] ++ API.
  *
  *   - `GET /__beard/events` SSE of [[HostMsg]] (`ZStream.fromHub`)
  *   - `POST /__beard/msg` body is one [[WebviewMsg]]
  */
object LiveMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      root = resolvePreviewRoot(args)
      port = resolvePort(args)
      _ <- ZIO.scoped {
        LiveSession.start(line => java.lang.System.err.println(s"[beard] $line")).flatMap { session =>
          ZIO.logInfo(s"Grok's Beard preview on http://localhost:$port serving $root") *>
            Server.serve(routes(root, port, session)).provide(Server.defaultWith(_.port(port)))
        }
      }
    yield ()

  def routes(previewRoot: JPath, port: Int, session: LiveSession): Routes[Any, Response] =
    Preview.routes(PreviewConfig(root = previewRoot, port = port)) ++ apiRoutes(session)

  def apiRoutes(session: LiveSession): Routes[Any, Response] =
    Routes(
      Method.GET / "__beard" / "events" -> handler { (_: Request) =>
        val stream = ZStream.fromHub(session.events).map(msg => ServerSentEvent(msg.toJson))
        Response.fromServerSentEvents(stream)
      },
      Method.POST / "__beard" / "msg" -> handler { (req: Request) =>
        req.body.asString.orDie.flatMap { raw =>
          raw.fromJson[WebviewMsg] match
            case Left(_)    => ZIO.succeed(Response.badRequest("invalid WebviewMsg"))
            case Right(msg) => session.post(msg).as(Response.ok)
        }
      },
    )

  def resolvePreviewRoot(args: Chunk[String]): JPath =
    val positional = args.filterNot(a => a == "--open" || a.forall(_.isDigit))
    positional.headOption
      .map(JPath.of(_))
      .getOrElse(JPath.of("beard/ui/target/preview"))
      .toAbsolutePath
      .normalize

  def resolvePort(args: Chunk[String]): Int =
    args.find(_.forall(_.isDigit)).map(_.toInt).getOrElse(8765)
end LiveMain

final class LiveSession(
    val events: Hub[HostMsg],
    handle: Ref[WebviewMsg => Unit],
):
  def post(msg: WebviewMsg): UIO[Unit] =
    handle.get.map(_(msg))

object LiveSession:
  def start(log: String => Unit): ZIO[Scope, Nothing, LiveSession] =
    val cwd = java.lang.System.getProperty("user.dir", ".")
    val env = (k: String) => Option(java.lang.System.getenv(k))
    val win = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    for
      events <- Hub.unbounded[HostMsg]
      handle <- Ref.make[WebviewMsg => Unit](_ => ())
      emit = (msg: HostMsg) =>
        Unsafe.unsafe { implicit u =>
          val _ = Runtime.default.unsafe.run(events.publish(msg)).getOrThrowFiberFailure()
        }
      located = CliLocator.locate(LocateGrok(None, env, win, p => Files.isRegularFile(JPath.of(p))))
      _ <- located match
        case Left(searched) =>
          val err = Onboarding.missingCliMessage(searched)
          log(err)
          handle.set {
            case WebviewMsg.Ready =>
              emit(HostMsg.Ready)
              emit(HostMsg.Error(err))
            case _ => emit(HostMsg.Error(err))
          }
        case Right(cmd) =>
          val args = Spawn.grokAgentStdioArgs()
          log(s"spawning $cmd ${args.mkString(" ")}")
          ProcessTransport.spawn(cmd, args, cwd).orDie.flatMap { transport =>
            val caps = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = false)
            val rt   = ChatRuntime(emit, transport, cwd = cwd, capabilities = caps)
            ZIO.addFinalizer(ZIO.succeed {
              log(s"closing grok agent (pid ${transport.pid})")
              rt.close()
            }) *> handle.set(msg => HostDispatch(rt, msg, emit))
          }
    yield LiveSession(events, handle)
    end for
  end start
end LiveSession
