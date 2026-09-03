package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object McpSpec extends ZIOSpecDefault:
  def spec =
    suite("MCP")(
      suite("SocketAddress")(
        test("uses XDG_RUNTIME_DIR and a 16-char workspace hash on unix") {
          val workspace = "/Users/russ/proj"
          val addr      = SocketAddress.address(workspace, win = false, digest16, "/run/user/501", identity)
          assertTrue(
            addr == s"/run/user/501/groks-beard/${digest16(workspace)}.sock",
            SocketAddress.hash16(workspace, digest16).length == 16,
          )
        },
        test("falls back to tmpdir when XDG_RUNTIME_DIR is unset") {
          val addr = SocketAddress.address("/tmp/ws", win = false, digest16, "/var/tmp", identity)
          assertTrue(addr == s"/var/tmp/groks-beard/${digest16("/tmp/ws")}.sock")
        },
        test("uses a named pipe on Windows and lowercases the workspace path") {
          val a = SocketAddress.address("C:\\Users\\Russ\\Proj", win = true, digest16, "/unused", identity)
          val b = SocketAddress.address("c:\\users\\russ\\proj", win = true, digest16, "/unused", identity)
          assertTrue(a == b, a == s"\\\\.\\pipe\\groks-beard-${digest16("c:\\users\\russ\\proj")}")
        },
      ),
      suite("NodeLocator")(
        test("setting wins, then PATH, and never uses process.execPath") {
          val found = NodeLocator.locate(
            LocateNode(
              nodePath = Some("/opt/node"),
              pathEnv = "/usr/bin:/bin",
              win = false,
              exists = p => p == "/opt/node" || p == "/usr/bin/node",
            )
          )
          assertTrue(found == Right("/opt/node"))
        },
        test("missing node returns the searched list") {
          val got = NodeLocator.locate(LocateNode(None, "/usr/bin", win = false, exists = _ => false))
          assertTrue(
            got == Left(List("/usr/bin/node")),
            !got.swap.toOption.get.exists(_.contains("execPath")),
          )
        },
        test("Windows .cmd resolves to .exe when it exists") {
          val got = NodeLocator.locate(
            LocateNode(None, "C:\\nodejs", win = true, exists = p => p.endsWith("node.cmd") || p.endsWith("node.exe"))
          )
          assertTrue(got.exists(_.endsWith("node.exe")))
        },
      ),
      suite("McpToml")(
        test("writes only the project .grok/config.toml path") {
          val path = McpToml.projectConfigPath("/repo")
          assertTrue(path == "/repo/.grok/config.toml", !path.contains("/Users/"), !path.startsWith("~"))
        },
        test("appends the groks-beard table while preserving comments") {
          val existing = "# team mcp\n[mcp_servers.other]\ncommand = \"npx\"\n"
          val table    = McpToml.renderTable("/usr/bin/node", "/ext/dist/mcp-proxy.js", "/repo")
          val merged   = McpToml.mergeTable(existing, table)
          assertTrue(
            merged.startsWith("# team mcp"),
            merged.contains("[mcp_servers.other]"),
            merged.contains("[mcp_servers.groks-beard]"),
            merged.contains("\"/usr/bin/node\""),
            merged.contains("\"--workspace\""),
            !merged.contains("execPath"),
          )
        },
        test("replaces an existing groks-beard table without dropping later tables") {
          val existing = "[mcp_servers.groks-beard]\ncommand = \"old\"\n\n[plugins]\nfoo = true\n"
          val table    = McpToml.renderTable("/bin/node", "/proxy.js", "/ws")
          val merged   = McpToml.mergeTable(existing, table)
          assertTrue(
            merged.contains("command = \"/bin/node\""),
            !merged.contains("command = \"old\""),
            merged.contains("[plugins]"),
          )
        },
        test("removes only the groks-beard table") {
          val existing =
            "# keep\n[mcp_servers.groks-beard]\ncommand = \"x\"\n\n[mcp_servers.other]\ncommand = \"y\"\n"
          val next = McpToml.removeTable(existing)
          assertTrue(next.contains("# keep"), next.contains("[mcp_servers.other]"), !next.contains("groks-beard"))
        },
        test("does not treat a mid-line [mcp_servers.groks-beard] mention as the table") {
          val existing = "note = \"see [mcp_servers.groks-beard]\"\n[mcp_servers.other]\ncommand = \"npx\"\n"
          val table    = McpToml.renderTable("/bin/node", "/proxy.js", "/ws")
          val merged   = McpToml.mergeTable(existing, table)
          assertTrue(
            merged.contains("see [mcp_servers.groks-beard]"),
            merged.contains("[mcp_servers.other]"),
            merged.contains("command = \"npx\""),
            merged.contains("command = \"/bin/node\""),
          )
        },
        test("replaces a groks-beard table in a CRLF file") {
          val existing = "[mcp_servers.groks-beard]\r\ncommand = \"old\"\r\n\r\n[plugins]\r\nfoo = true\r\n"
          val table    = McpToml.renderTable("/bin/node", "/proxy.js", "/ws")
          val merged   = McpToml.mergeTable(existing, table)
          assertTrue(
            merged.contains("command = \"/bin/node\""),
            !merged.contains("command = \"old\""),
            merged.contains("[plugins]"),
          )
        },
      ),
      suite("McpTools")(
        test("lists only read-only path tools") {
          val names = McpTools.Specs.map(_.name)
          assertTrue(
            names == McpTool.Names,
            names.forall(n => !n.contains("write") && !n.contains("apply") && !n.contains("terminal")),
            McpTools.Specs.forall(_.description.nonEmpty),
          )
        },
        test("rejects editor_open_diff without a path") {
          val got = McpTools.dispatch("editor_open_diff", Json.Obj(), FakeHost())
          assertTrue(got.isLeft)
        },
        test("rejects editor_open_diff and editor_show_changes surplus file bodies") {
          val extraDiff = McpTools.dispatch(
            "editor_open_diff",
            Json.Obj("path" -> Json.Str("a.ts"), "oldText" -> Json.Str("x"), "newText" -> Json.Str("y")),
            FakeHost(),
          )
          val extraShow = McpTools.dispatch(
            "editor_show_changes",
            Json.Obj(
              "files" -> Json.Arr(
                Json.Obj("path" -> Json.Str("a.ts"), "kind" -> Json.Str("modify"), "oldText" -> Json.Str("x"))
              )
            ),
            FakeHost(),
          )
          val diffSpec = McpTools.Specs.find(_.name == "editor_open_diff").get
          val showSpec = McpTools.Specs.find(_.name == "editor_show_changes").get
          assertTrue(
            extraDiff.isLeft,
            extraShow.isLeft,
            diffSpec.inputSchema.toString.contains("additionalProperties"),
            showSpec.inputSchema.toString.contains("additionalProperties"),
          )
        },
        test("dispatches editor_show_changes with a non-empty files list") {
          val host = FakeHost()
          val got  = McpTools.dispatch(
            "editor_show_changes",
            Json.Obj(
              "title" -> Json.Str("TUI edits"),
              "files" -> Json.Arr(Json.Obj("path" -> Json.Str("src/a.ts"), "kind" -> Json.Str("modify"))),
            ),
            host,
          )
          assertTrue(got == Right(Json.Obj("ok" -> Json.Bool(true), "shown" -> Json.Num(1))), host.shown == 1)
        },
        test("selection truncates over the MCP byte cap and still emits atRef") {
          val big  = "x" * (McpTool.SelectionCapBytes + 50)
          val json = McpTools.selectionJson(Some("a.ts"), Some("/repo/a.ts"), Some(1), Some(4), Some(big), None)
          json match
            case obj: Json.Obj =>
              val text = obj.get("text").collect { case Json.Str(s) => s }.getOrElse("")
              assertTrue(
                obj.get("truncated") == Some(Json.Bool(true)),
                Utf8.byteLength(text) <= McpTool.SelectionCapBytes,
                obj.get("atRef") == Some(Json.Str("@a.ts:1-4")),
              )
            case _ => assertTrue(false)
        },
      ),
      suite("McpStdio")(
        test("parses --workspace from argv") {
          assertTrue(
            McpStdio.parseWorkspaceArg(List("--workspace", "/abs/ws")).contains("/abs/ws"),
            McpStdio.parseWorkspaceArg(List("--workspace=/abs/ws")).contains("/abs/ws"),
            McpStdio.parseWorkspaceArg(List("--help")).isEmpty,
          )
        },
        test("initialize lists the negotiated protocol and tools/list is read-only") {
          val init = McpStdio.handle(
            Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "id"      -> Json.Num(1),
              "method"  -> Json.Str("initialize"),
              "params"  -> Json.Obj("protocolVersion" -> Json.Str("2025-03-26")),
            ),
            FakeHost(),
          )
          val listed = McpStdio.handle(
            Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(2), "method" -> Json.Str("tools/list")),
            FakeHost(),
          )
          val initOk = init.exists {
            case obj: Json.Obj =>
              obj.get("result") match
                case Some(r: Json.Obj) =>
                  r.get("protocolVersion") == Some(Json.Str("2025-03-26")) &&
                  r.get("serverInfo").isDefined
                case _ => false
            case _ => false
          }
          val listOk = listed.exists {
            case obj: Json.Obj =>
              obj.get("result") match
                case Some(r: Json.Obj) =>
                  r.get("tools") match
                    case Some(Json.Arr(tools)) =>
                      tools.size == 6 && tools.forall {
                        case t: Json.Obj =>
                          t.get("annotations") == Some(McpTools.Annotations)
                        case _ => false
                      }
                    case _ => false
                case _ => false
            case _ => false
          }
          assertTrue(initOk, listOk)
        },
        test("tools/call editor_workspace_root round-trips through a fake bridge") {
          val host = FakeHost()
          val got  = McpStdio.handle(
            Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "id"      -> Json.Num(3),
              "method"  -> Json.Str("tools/call"),
              "params"  -> Json.Obj("name" -> Json.Str("editor_workspace_root"), "arguments" -> Json.Obj()),
            ),
            host,
          )
          val text = got.flatMap {
            case obj: Json.Obj =>
              obj.get("result") match
                case Some(r: Json.Obj) =>
                  r.get("content") match
                    case Some(Json.Arr(items)) =>
                      items.headOption.collect { case o: Json.Obj =>
                        o.get("text").collect { case Json.Str(s) => s }
                      }.flatten
                    case _ => None
                case _ => None
            case _ => None
          }
          assertTrue(text.exists(_.contains("/repo")))
        },
        test("unknown MCP methods are -32601") {
          val got = McpStdio.handle(
            Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(9), "method" -> Json.Str("nope")),
            FakeHost(),
          )
          val code = got.flatMap {
            case obj: Json.Obj =>
              obj.get("error") match
                case Some(e: Json.Obj) =>
                  e.get("code") match
                    case Some(Json.Num(n)) => Some(n.intValue)
                    case _                 => None
                case _ => None
            case _ => None
          }
          assertTrue(code.contains(-32601))
        },
        test("bridge down is tagged -32002 with Enable TUI Bridge copy") {
          val got = McpStdio.handle(
            Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "id"      -> Json.Num(4),
              "method"  -> Json.Str("tools/call"),
              "params"  -> Json.Obj("name" -> Json.Str("editor_workspace_root")),
            ),
            FakeHost(),
            (_, _) => Left(McpToml.EditorDownMessage),
          )
          val msg = got.flatMap {
            case obj: Json.Obj =>
              obj.get("error") match
                case Some(e: Json.Obj) =>
                  e.get("message").collect { case Json.Str(s) => s }
                case _ => None
            case _ => None
          }
          assertTrue(msg.contains(McpToml.EditorDownMessage), msg.exists(_.contains("Enable TUI Bridge")))
        },
      ),
    )

  // Injected into SocketAddress. Do not use java.security; it is absent on Scala.js.
  private def digest16(text: String): String =
    val hex = text.map(c => f"${c.toInt & 0xff}%02x").mkString
    (hex + "0" * 16).take(16)

  private final class FakeHost extends McpToolHost:
    var shown: Int                              = 0
    def workspaceRoot(): Json                   = Json.Obj("root" -> Json.Str("/repo"))
    def selection(): Json                       = Json.Obj("truncated" -> Json.Bool(false))
    def openFiles(cursor: Option[String]): Json =
      Json.Obj("tabs" -> Json.Arr(), "truncated" -> Json.Bool(false))
    def reveal(path: String, line: Option[Int]): Json                           = Json.Obj("ok" -> Json.Bool(true))
    def openDiff(path: String, line: Option[Int]): Json                         = Json.Obj("ok" -> Json.Bool(true))
    def showChanges(title: Option[String], files: List[(String, String)]): Json =
      shown = files.size
      Json.Obj("ok" -> Json.Bool(true), "shown" -> Json.Num(files.size))
  end FakeHost
end McpSpec
