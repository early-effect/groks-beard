package groksbeard.preview

import ascent.preview.{Preview, PreviewConfig}
import groksbeard.core.*
import zio.*
import zio.http.*
import zio.json.*

import java.nio.file.Path as JPath

/** Same-origin preview + live `grok agent stdio`. One SSE + ChatRuntime + agent per browser client.
  *
  *   - `GET /__beard/events?client=` SSE of [[HostMsg]]
  *   - `POST /__beard/msg?client=` body is one [[WebviewMsg]]
  *
  * [[Preview.serve]] owns static files, stamp reload, and the HTTP scope. Clients are acquired in that same `Scope`
  * before bind so extra routes cannot race the resource.
  */
object LiveMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      config = configFromArgs(args)
      log    = (line: String) => java.lang.System.err.println(s"[beard] $line")
      clients <- LiveClients.grok(log)
      _       <- ZIO.logInfo(s"Grok's Beard preview on http://localhost:${config.port} serving ${config.root}")
      _       <- Preview
        .serve(config, extraRoutes = apiRoutes(clients))
        .provideSome[Scope](Server.defaultWith(_.port(config.port)))
    yield ()

  def apiRoutes(clients: LiveClients): Routes[Any, Response] =
    Routes(
      Method.GET / "__beard" / "events" -> handler { (req: Request) =>
        clientId(req) match
          case None     => ZIO.succeed(Response.badRequest("missing client"))
          case Some(id) =>
            val stream = clients.eventStream(id).map(msg => ServerSentEvent(msg.toJson))
            ZIO.succeed(Response.fromServerSentEvents(stream))
      },
      Method.POST / "__beard" / "msg" -> handler { (req: Request) =>
        clientId(req) match
          case None     => ZIO.succeed(Response.badRequest("missing client"))
          case Some(id) =>
            req.body.asString.orDie.flatMap { raw =>
              raw.fromJson[WebviewMsg] match
                case Left(_)    => ZIO.succeed(Response.badRequest("invalid WebviewMsg"))
                case Right(msg) => clients.post(id, msg).as(Response.ok)
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
      .getOrElse(JPath.of("beard/ui/target/preview"))
      .toAbsolutePath
      .normalize
    PreviewConfig(root = root, port = port, openBrowser = open)
  end configFromArgs
end LiveMain
