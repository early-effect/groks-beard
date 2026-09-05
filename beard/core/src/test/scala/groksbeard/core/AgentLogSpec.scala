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
          AgentLog.classify("info: ready").isEmpty,
        )
      }
    )
end AgentLogSpec
