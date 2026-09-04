package groksbeard.host

import groksbeard.core.*
import zio.json.*

import scala.scalajs.js

final class TuiBridge(host: McpToolHost, log: String => Unit):
  private var server: Option[NodeServer] = None

  def listening: Boolean = server.isDefined

  def address(workspace: String): String =
    val win     = nodeProcess.platform == "win32"
    val runtime =
      nodeProcess.env.get("XDG_RUNTIME_DIR").flatMap(v => v.toOption).filter(_.nonEmpty).getOrElse(nodeOs.tmpdir())
    SocketAddress.address(workspace, win, TuiBridge.sha256Hex, runtime)

  def sync(enabled: Boolean, workspace: Option[String]): Unit =
    if !enabled || workspace.isEmpty then unbind()
    else if server.isEmpty then bind(workspace.get)

  def unbind(): Unit =
    server.foreach { s =>
      val _ = s.close()
    }
    server = None

  private def bind(workspace: String): Unit =
    val addr = address(workspace)
    if !addr.startsWith("\\\\.\\pipe\\") then
      nodeFs.mkdirSync(nodePath.dirname(addr), js.Dynamic.literal(recursive = true, mode = SocketAddress.SocketDirMode))
      try nodeFs.unlinkSync(addr)
      catch case _: Throwable => ()
    val srv = nodeNet.createServer { (socket: NodeSocket) =>
      var buffer = ""
      socket.setEncoding("utf8")
      socket.on(
        "data",
        (chunk: js.Any) =>
          val (lines, rest) = Ndjson.split(buffer, "" + chunk)
          buffer = rest
          lines.foreach { line =>
            val reply =
              line.fromJson[BridgeRequest] match
                case Left(_)    => McpBridge.fail("", "invalid json")
                case Right(req) =>
                  McpTools.dispatch(req.tool, req.args, host) match
                    case Left(err)  => McpBridge.fail(req.id, err)
                    case Right(res) => McpBridge.ok(req.id, res)
            val _ = socket.write(Ndjson.encode(reply.toJson))
            socket.end()
          },
      )
      socket.on("error", (_: js.Any) => ())
    }
    srv.on("error", (err: js.Any) => log("TUI bridge error: " + err))
    srv.listen(
      addr,
      () =>
        try nodeFs.chmodSync(addr, SocketAddress.SocketMode)
        catch case _: Throwable => ()
        log(s"TUI bridge listening on $addr"),
    )
    server = Some(srv)
  end bind
end TuiBridge

object TuiBridge:
  val StateKey = "groksBeard.tuiBridge.enabled"

  def sha256Hex(text: String): String =
    nodeCrypto.createHash("sha256").update(text).digest("hex")
