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
  */
object LiveMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      root = resolvePreviewRoot(args)
      port = resolvePort(args)
      log  = (line: String) => java.lang.System.err.println(s"[beard] $line")
      _ <- ZIO.scoped {
        LiveClients.grok(log).flatMap { clients =>
          ZIO.logInfo(s"Grok's Beard preview on http://localhost:$port serving $root") *>
            Server.serve(routes(root, port, clients)).provide(Server.defaultWith(_.port(port)))
        }
      }
    yield ()

  def routes(previewRoot: JPath, port: Int, clients: LiveClients): Routes[Any, Response] =
    Preview.routes(PreviewConfig(root = previewRoot, port = port)) ++ apiRoutes(clients)

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
