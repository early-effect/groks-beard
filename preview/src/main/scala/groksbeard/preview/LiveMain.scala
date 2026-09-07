package groksbeard.preview

import ascent.preview.{Preview, PreviewConfig}
import groksbeard.core.*
import groksbeard.core.Wire
import zio.*
import zio.http.*
import zio.json.*

import java.nio.file.Path as JPath

/** Same-origin preview + live `grok agent stdio`.
  *
  *   - `GET /__beard/events?client=` SSE of [[HostMsg]]
  *   - `POST /__beard/msg?client=` body is one [[WebviewMsg]]
  *
  * [[Preview.serve]] owns static files, stamp reload, and HTTP. The grok host is a stamp-scoped sidecar: `~` rebuilds
  * rewrite the stamp, the sidecar is interrupted and acquired again, HTTP stays up. Extra routes read a [[Ref]] so they
  * do not close over a process-lifetime [[LiveClients]].
  */
object LiveMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      config = configFromArgs(args)
      log    = (line: String) => java.lang.System.err.println(s"[beard] $line")
      holder <- Ref.make(Option.empty[LiveClients])
      _      <- ZIO.logInfo(s"Grok's Beard preview on http://localhost:${config.port} serving ${config.root}")
      _      <- Preview
        .serve(
          config,
          extraRoutes = apiRoutes(holder),
          sidecar = beardSidecar(holder, log),
          restartSidecarOnStamp = true,
        )
        .provideSome[Scope](Server.defaultWith(_.port(config.port)))
    yield ()

  def beardSidecar(holder: Ref[Option[LiveClients]], log: String => Unit): ZIO[Scope, Throwable, Unit] =
    LiveClients.grok(log).flatMap { clients =>
      ZIO.logInfo("beard sidecar up") *> hold(holder, clients)
    }

  def hold(holder: Ref[Option[LiveClients]], clients: LiveClients): ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(holder.set(Some(clients)))(_ => holder.set(None))

  def apiRoutes(clients: LiveClients): Routes[Any, Response] =
    apiRoutes(ZIO.succeed(Some(clients)))

  def apiRoutes(holder: Ref[Option[LiveClients]]): Routes[Any, Response] =
    apiRoutes(holder.get)

  def apiRoutes(current: UIO[Option[LiveClients]]): Routes[Any, Response] =
    Routes(
      Method.GET / "__beard" / "events" -> handler { (req: Request) =>
        clientId(req) match
          case None     => ZIO.succeed(Response.badRequest("missing client"))
          case Some(id) =>
            current.flatMap {
              case None          => ZIO.succeed(Response.status(Status.ServiceUnavailable))
              case Some(clients) =>
                val stream = clients.eventStream(id).map(msg => ServerSentEvent(msg.toJson))
                ZIO.succeed(Response.fromServerSentEvents(stream))
            }
      },
      Method.POST / "__beard" / "msg" -> handler { (req: Request) =>
        clientId(req) match
          case None     => ZIO.succeed(Response.badRequest("missing client"))
          case Some(id) =>
            current.flatMap {
              case None          => ZIO.succeed(Response.status(Status.ServiceUnavailable))
              case Some(clients) =>
                req.body.asString.orDie.flatMap { raw =>
                  Wire.webview(raw) match
                    case Left(err) =>
                      ZIO.logError(err) *> clients
                        .emit(id, HostMsg.Error(err, Some(Wire.Decode)))
                        .as(Response.badRequest(err))
                    case Right(msg) => clients.post(id, msg).as(Response.ok)
                }
            }
      },
    )

  def clientId(req: Request): Option[String] =
    req.queryParam("client").map(_.trim).filter(LiveClients.validId)

  def configFromArgs(args: Chunk[String]): PreviewConfig =
    val open       = args.contains("--open")
    val positional = args.filterNot(_ == "--open")
    val port       = positional.headOption.filter(_.forall(_.isDigit)).map(_.toInt).getOrElse(8765)
    val root       = positional
      .lift(1)
      .map(JPath.of(_))
      .getOrElse(JPath.of("ui/target/preview"))
      .toAbsolutePath
      .normalize
    PreviewConfig(root = root, port = port, openBrowser = open)
  end configFromArgs
end LiveMain
