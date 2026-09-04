package groksbeard.mcp

import groksbeard.core.*
import zio.json.*
import zio.json.ast.Json

import scala.scalajs.js

object Main:
  def main(args: Array[String]): Unit =
    val argv      = process.argv.toList.drop(2)
    val workspace = McpStdio.parseWorkspaceArg(argv).orElse(McpStdio.parseWorkspaceArg(args.toList))
    workspace match
      case None =>
        val _ = process.stderr.write("mcp-proxy: missing --workspace <absolute-path>\n")
        process.exit(2)
      case Some(ws) =>
        val address = addressFor(ws)
        probe(
          address,
          () => serve(ws, address),
          () =>
            val _ = process.stderr.write(s"${McpToml.EditorDownMessage}\n")
            process.exit(1),
        )
    end match
  end main

  def sha256Hex(text: String): String =
    crypto.createHash("sha256").update(text).digest("hex")

  def addressFor(workspace: String): String =
    val win     = process.platform == "win32"
    val runtime =
      process.env.get("XDG_RUNTIME_DIR").flatMap(v => v.toOption).filter(_.nonEmpty).getOrElse(os.tmpdir())
    SocketAddress.address(workspace, win, sha256Hex, runtime)

  private def probe(address: String, ok: () => Unit, fail: () => Unit): Unit =
    val sock                           = net.connect(address)
    var done                           = false
    def finish(success: Boolean): Unit =
      if !done then
        done = true
        sock.end()
        sock.destroy()
        if success then ok() else fail()
    sock.setTimeout(2000, () => finish(false))
    sock.once("connect", (_: js.Any) => finish(true))
    sock.once("error", (_: js.Any) => finish(false))
  end probe

  private def serve(workspace: String, address: String): Unit =
    var buffer                  = ""
    var nextId                  = 0
    var settled                 = false
    def finish(code: Int): Unit =
      if !settled then
        settled = true
        process.exit(code)
    process.stdin.setEncoding("utf8")
    process.stdin.on(
      "data",
      (chunk: js.Any) =>
        if !settled then
          val (lines, rest) = Ndjson.split(buffer, "" + chunk)
          buffer = rest
          lines.foreach { line =>
            line.fromJson[Json].foreach { json =>
              McpStdio.classify(json) match
                case McpAction.Ignore          => ()
                case McpAction.Reply(response) =>
                  val _ = process.stdout.write(Ndjson.encode(response.toJson))
                case McpAction.CallTool(id, name, args) =>
                  nextId += 1
                  callBridge(
                    address,
                    nextId.toString,
                    name,
                    args,
                    result =>
                      val _ = process.stdout.write(Ndjson.encode(McpStdio.wrapCall(id, name, result).toJson))
                    ,
                    () =>
                      val _ = process.stdout.write(
                        Ndjson.encode(McpStdio.wrapCall(id, name, Left(McpToml.EditorDownMessage)).toJson)
                      )
                      val _ = process.stderr.write(s"${McpToml.EditorDownMessage}\n")
                      finish(1),
                  )
            }
          },
    )
    process.stdin.on("end", (_: js.Any) => finish(0))
    process.stdin.on("error", (_: js.Any) => finish(1))
    val _ = workspace
  end serve

  private def callBridge(
      address: String,
      id: String,
      tool: String,
      args: Json,
      ok: Either[String, Json] => Unit,
      down: () => Unit,
  ): Unit =
    val sock                                                        = net.connect(address)
    var buf                                                         = ""
    var done                                                        = false
    def finish(result: Either[String, Json], isDown: Boolean): Unit =
      if !done then
        done = true
        sock.end()
        sock.destroy()
        if isDown then down() else ok(result)
    sock.setEncoding("utf8")
    sock.setTimeout(5000, () => finish(Left(McpToml.EditorDownMessage), isDown = true))
    sock.once(
      "connect",
      (_: js.Any) =>
        val _ = sock.write(Ndjson.encode(McpBridge.request(id, tool, args).toJson)),
    )
    sock.on(
      "data",
      (chunk: js.Any) =>
        val (lines, rest) = Ndjson.split(buf, "" + chunk)
        buf = rest
        lines.headOption.foreach { line =>
          val parsed = line.fromJson[BridgeResponse].left.map(_ => "invalid json").flatMap { resp =>
            McpBridge.parseResponse(resp.asJson)
          }
          finish(parsed, isDown = false)
        },
    )
    sock.once("error", (_: js.Any) => finish(Left(McpToml.EditorDownMessage), isDown = true))
  end callBridge
end Main
