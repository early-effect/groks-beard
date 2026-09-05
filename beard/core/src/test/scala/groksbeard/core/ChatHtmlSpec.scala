package groksbeard.core

import zio.test.*

object ChatHtmlSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatHtml")(
      test("webview CSP has no unsafe-eval") {
        val html =
          ChatHtml.page("https://example.vscode-cdn.net", "/chat.js", Some("/logo.png"), ctrlEnterToSend = false)
        assertTrue(
          !ChatHtml.hasUnsafeEval(html),
          html.contains("script-src https://example.vscode-cdn.net"),
          html.contains("connect-src 'none'"),
          html.contains("id=\"root\""),
          html.contains("data-logo=\"/logo.png\""),
          html.contains("<html lang=\"en\" data-logo=\"/logo.png\">"),
        )
      }
    )
end ChatHtmlSpec
