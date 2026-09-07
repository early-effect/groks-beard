package groksbeard.core

import ascent.squawk.Eq
import zio.*
import zio.json.*
import zio.json.ast.Json

final case class McpServerView(
    name: String,
    transport: String,
    target: String,
    source: String = "",
    vendor: Option[String] = None,
    enabled: Boolean = true,
    toolCount: Option[Int] = None,
    healthy: Option[Boolean] = None,
) derives JsonCodec,
      Eq

trait Mcps:
  def list: BeardError.Result[List[McpServerView]]
  def setEnabled(name: String, enabled: Boolean): BeardError.Result[List[McpServerView]]

object Mcps:
  val none: ULayer[Mcps] = test()

  def test(initial: List[McpServerView] = Nil): ULayer[Mcps] =
    ZLayer.succeed {
      val lock = new Object
      var rows = initial
      new Mcps:
        def list: BeardError.Result[List[McpServerView]] = ZIO.succeed(lock.synchronized(rows))
        def setEnabled(name: String, enabled: Boolean): BeardError.Result[List[McpServerView]] =
          ZIO.succeed {
            lock.synchronized {
              rows = rows.map { row =>
                if row.name == name then row.copy(enabled = enabled) else row
              }
              rows
            }
          }
      end new
    }

  def cli(
      grok: List[String] => BeardError.Result[String],
      userToml: UIO[String] = ZIO.succeed(""),
  ): Mcps =
    new Mcps:
      def list: BeardError.Result[List[McpServerView]] =
        grok(List("inspect", "--json")).flatMap { raw =>
          decodeInspect(raw) match
            case Left(err)   => ZIO.fail(BeardError.decode(err))
            case Right(rows) =>
              userToml.map(toml => applyDisabled(rows, disabledNames(toml)))
        }

      def setEnabled(name: String, enabled: Boolean): BeardError.Result[List[McpServerView]] =
        val verb = if enabled then "enable" else "disable"
        grok(List("mcp", verb, name)) *> list

  def decodeInspect(raw: String): Either[String, List[McpServerView]] =
    raw.fromJson[Json] match
      case Left(err)   => Left(err)
      case Right(json) =>
        serversOf(json) match
          case None        => Left("inspect json: missing mcpServers")
          case Some(items) => Right(items.flatMap(serverOf))

  def disabledNames(toml: String): Set[String] =
    val key = "disabled_mcp_servers"
    val at  = toml.indexOf(key)
    if at < 0 then Set.empty
    else
      val from = toml.indexOf('[', at)
      val to   = if from < 0 then -1 else toml.indexOf(']', from)
      if from < 0 || to < 0 then Set.empty
      else
        val body = toml.substring(from, to + 1)
        """"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).toSet
  end disabledNames

  def applyDisabled(rows: List[McpServerView], disabled: Set[String]): List[McpServerView] =
    if disabled.isEmpty then rows
    else
      rows.map { row =>
        if disabled.contains(row.name) then row.copy(enabled = false, healthy = Some(false))
        else row
      }

  def sourceLabel(path: String, home: String = ""): String =
    val compact =
      if home.nonEmpty && path.startsWith(home) then
        val rest = path.substring(home.length).replaceAll("^[/\\\\]+", "")
        s"~/$rest"
      else path
    val slash = compact.replace('\\', '/')
    slash match
      case s if s.endsWith("/.cursor/mcp.json") || s.endsWith("/cursor/mcp.json") =>
        "~/.cursor/mcp.json"
      case s if s.endsWith("/.mcp.json") || s == ".mcp.json" =>
        ".mcp.json"
      case s if s.endsWith(".claude.json")                       => "~/.claude.json"
      case s if s.endsWith("config.toml") && s.contains(".grok") =>
        if s.contains("~") || s.contains("home") then "~/.grok/config.toml"
        else ".grok/config.toml"
      case s => s
    end match
  end sourceLabel

  def status(row: McpServerView): String =
    if !row.enabled then "disabled"
    else
      row.healthy match
        case Some(false) => "unhealthy"
        case Some(true)  => row.toolCount.map(n => s"$n tools").getOrElse("ready")
        case None        => row.transport

  def headline(rows: List[McpServerView]): String =
    if rows.isEmpty then "MCP servers"
    else
      val on = rows.count(_.enabled)
      s"MCP servers · $on/${rows.size} on"

  private def serversOf(json: Json): Option[List[Json]] =
    json match
      case Json.Obj(fields) =>
        fields.collectFirst {
          case ("mcpServers", Json.Arr(xs)) => xs.toList
          case ("servers", Json.Arr(xs))    => xs.toList
        }
      case _ => None

  private def serverOf(json: Json): Option[McpServerView] =
    json match
      case Json.Obj(fields) =>
        def str(key: String): Option[String] =
          fields.collectFirst {
            case (k, Json.Str(s)) if k == key => s
          }
        def bool(key: String): Option[Boolean] =
          fields.collectFirst {
            case (k, Json.Bool(b)) if k == key => b
          }
        str("name").filter(_.nonEmpty).map { name =>
          val src     = sourceOf(fields)
          val enabled =
            str("compatibilityStatus").map(_ != "disabled").orElse(bool("enabled")).getOrElse(true)
          McpServerView(
            name = name,
            transport = str("transport").getOrElse(""),
            target = str("target").getOrElse(""),
            source = src,
            vendor = str("vendor").filter(_.nonEmpty),
            enabled = enabled,
          )
        }
      case _ => None

  private def sourceOf(fields: Chunk[(String, Json)]): String =
    fields
      .collectFirst {
        case ("source", Json.Str(s))     => s
        case ("source", Json.Obj(inner)) =>
          inner
            .collectFirst { case ("path", Json.Str(p)) => p }
            .orElse(inner.collectFirst { case ("type", Json.Str(t)) => t })
            .getOrElse("")
      }
      .getOrElse("")
end Mcps
