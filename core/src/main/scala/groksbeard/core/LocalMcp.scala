package groksbeard.core

import zio.*
import zio.json.ast.Json
import zio.json.*

/** Wait for project `.mcp.json` HTTP servers on localhost before ACP initialize. Metals and other editor MCP endpoints
  * often listen only after the window is up.
  */
object LocalMcp:
  val Attempt: Duration = 400.millis
  val Cap: Duration     = 5.seconds
  val Limit: Duration   = 2.minutes

  def waitingNotice(url: String): String =
    s"Waiting for local MCP at $url."

  def timeoutNotice(url: Option[String]): String =
    url.filter(_.nonEmpty) match
      case Some(u) => s"An MCP server at $u is not running yet. Other tools still work."
      case None    => "An MCP server is not running yet. Other tools still work."

  def localHttpUrls(raw: String): List[String] =
    raw.fromJson[Json].toOption match
      case Some(Json.Obj(fields)) =>
        val servers = fields
          .collectFirst {
            case ("mcpServers", Json.Obj(ss))  => ss
            case ("mcp_servers", Json.Obj(ss)) => ss
          }
          .getOrElse(Chunk.empty)
        servers.toList.flatMap { case (_, v) =>
          urlOf(v).filter(isLocalHttp)
        }
      case _ => Nil

  def hostPort(url: String): Option[(String, Int)] =
    try
      val u      = new java.net.URI(url)
      val host   = Option(u.getHost).filter(_.nonEmpty)
      val scheme = Option(u.getScheme).getOrElse("http")
      val port   = u.getPort
      val p      = if port > 0 then port else if scheme == "https" then 443 else 80
      host.map(_ -> p)
    catch case _: Throwable => None

  def isLocalHttp(url: String): Boolean =
    hostPort(url).exists { (host, _) =>
      val h = host.toLowerCase
      h == "localhost" || h == "127.0.0.1" || h == "::1"
    }

  def awaitLocalHttp(
      read: UIO[Option[String]],
      open: String => UIO[Boolean],
      notify: String => UIO[Unit],
      log: String => UIO[Unit] = _ => ZIO.unit,
  ): UIO[Unit] =
    read.map(_.toList.flatMap(localHttpUrls)).flatMap { urls =>
      if urls.isEmpty then ZIO.unit
      else ZIO.foreachParDiscard(urls)(url => waitOne(url, open, notify, log))
    }

  private val backoff: Schedule[Any, Any, (Duration, Duration)] =
    Schedule.exponential(Attempt).delayed(d => d.min(Cap)) &&
      Schedule.elapsed.whileOutput(_ < Limit)

  private def waitOne(
      url: String,
      open: String => UIO[Boolean],
      notify: String => UIO[Unit],
      log: String => UIO[Unit],
  ): UIO[Unit] =
    open(url).flatMap {
      case true  => ZIO.unit
      case false =>
        log(s"local MCP $url try 1") *>
          notify(waitingNotice(url)) *>
          Ref.make(1).flatMap { tries =>
            val probe =
              tries.updateAndGet(_ + 1).flatMap { n =>
                open(url).flatMap {
                  case true  => log(s"local MCP $url is up (try $n)")
                  case false => log(s"local MCP $url try $n") *> ZIO.fail(())
                }
              }
            probe
              .retry(backoff)
              .foldZIO(
                _ => notify(timeoutNotice(Some(url))),
                _ => notify(""),
              )
          }
    }

  private def urlOf(value: Json): Option[String] =
    value match
      case Json.Obj(fields) =>
        fields.collectFirst { case ("url", Json.Str(u)) => u.trim }.filter(_.nonEmpty)
      case _ => None
end LocalMcp
