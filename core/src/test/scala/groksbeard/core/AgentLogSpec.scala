package groksbeard.core

import zio.test.*

object AgentLogSpec extends ZIOSpecDefault:
  def spec =
    suite("AgentLog")(
      test("AuthRequired on an MCP host becomes copy and is not ignored") {
        val line =
          """ERROR worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer resource_metadata=\"https://mcp.atlassian.com/.well-known/oauth-protected-resource/v1/mcp/authv2\", error=\"invalid_token\", error_description=\"Missing or invalid access token\"" })"""
        val got = AgentLog.classify(line)
        assertTrue(
          got.exists(_.toLowerCase.contains("atlassian")),
          got.exists(_.toLowerCase.contains("authentication")),
          got.exists(m => !m.toLowerCase.contains("stopped") && !m.toLowerCase.contains("turn")),
          AgentLog.classify("info: ready").isEmpty,
        )
      },
      test("connection refused to a local MCP is a notice") {
        val line =
          """ERROR worker quit with fatal: Transport channel closed, when Client(reqwest::Error { kind: Request, url: "http://localhost:56126/mcp", source: ConnectError("tcp connect error", Os { code: 61, kind: ConnectionRefused, message: "Connection refused" }) })"""
        assertTrue(
          AgentLog.classify(line).contains(LocalMcp.timeoutNotice(Some("http://localhost:56126/mcp"))),
          AgentLog.stripAnsi("\u001b[31mERROR\u001b[0m worker") == "ERROR worker",
        )
      },
    )
end AgentLogSpec
