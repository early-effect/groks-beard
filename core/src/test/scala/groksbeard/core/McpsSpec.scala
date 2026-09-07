package groksbeard.core

import zio.*
import zio.test.*

object McpsSpec extends ZIOSpecDefault:
  private val inspectJson =
    """{"mcpServers":[
      |{"name":"metals","transport":"http","target":"http://localhost:56126/mcp",
      |"source":{"type":"mcpJson","path":"/Users/russ/projects/fun/groks-beard/.mcp.json"}},
      |{"name":"atlassian","transport":"http","target":"https://mcp.atlassian.com/v1/mcp",
      |"source":{"type":"claudeJson","path":"/Users/russ/.claude.json"},
      |"compatibilityStatus":"enabled","vendor":"claude"},
      |{"name":"playwright-firefox","transport":"stdio","target":"/bin/bash",
      |"source":{"type":"mcpJson","path":"/Users/russ/.cursor/mcp.json"},
      |"compatibilityStatus":"enabled","vendor":"cursor"}
      |]}""".stripMargin

  def spec =
    suite("Mcps")(
      test("decodes grok inspect --json mcpServers") {
        val rows = Mcps.decodeInspect(inspectJson).toOption.get
        assertTrue(
          rows.map(_.name) == List("metals", "atlassian", "playwright-firefox"),
          rows.head.transport == "http",
          rows.head.target.contains("56126"),
          rows.head.source.endsWith(".mcp.json"),
          rows(1).vendor.contains("claude"),
          rows(2).vendor.contains("cursor"),
        )
      },
      test("marks disabled_mcp_servers from user toml") {
        val rows     = Mcps.decodeInspect(inspectJson).toOption.get
        val disabled = Mcps.disabledNames("""disabled_mcp_servers = ["atlassian"]""")
        val next     = Mcps.applyDisabled(rows, disabled)
        assertTrue(
          disabled == Set("atlassian"),
          next.find(_.name == "atlassian").exists(!_.enabled),
          next.find(_.name == "metals").exists(_.enabled),
        )
      },
      test("compacts source paths") {
        assertTrue(
          Mcps.sourceLabel("/Users/russ/projects/fun/groks-beard/.mcp.json") == ".mcp.json",
          Mcps.sourceLabel("/Users/russ/.cursor/mcp.json") == "~/.cursor/mcp.json",
          Mcps.sourceLabel("/Users/russ/.claude.json") == "~/.claude.json",
        )
      },
      test("cli lists then enable writes through grok") {
        for
          calls <- Ref.make(List.empty[List[String]])
          toml = ZIO.succeed("""disabled_mcp_servers = ["atlassian"]""")
          grok = (args: List[String]) =>
            calls.update(_ :+ args).as {
              args match
                case "inspect" :: _ => inspectJson
                case "mcp" :: _     => "ok"
                case _              => ""
            }
          svc = Mcps.cli(grok, toml)
          listed <- svc.list
          _      <- svc.setEnabled("atlassian", enabled = true)
          ran    <- calls.get
        yield assertTrue(
          listed.find(_.name == "atlassian").exists(!_.enabled),
          listed.exists(_.name == "metals"),
          ran.head == List("inspect", "--json"),
          ran.contains(List("mcp", "enable", "atlassian")),
        )
      },
      test("status copy") {
        val off = McpServerView("atlassian", "http", "https://x", enabled = false)
        val on  = McpServerView(
          "metals",
          "http",
          "http://localhost",
          enabled = true,
          toolCount = Some(17),
          healthy = Some(true),
        )
        assertTrue(
          Mcps.status(off) == "disabled",
          Mcps.status(on) == "17 tools",
          Mcps.headline(List(off, on)) == "MCP servers · 1/2 on",
          Mcps.headline(Nil) == "MCP servers",
        )
      },
    )
end McpsSpec
