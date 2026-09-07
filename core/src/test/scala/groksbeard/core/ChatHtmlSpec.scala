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
      },
      test("error page escapes markup and names the output channel") {
        val html = ChatHtml.errorPage("""<script>alert("x")</script>""")
        assertTrue(
          !html.contains("<script>alert"),
          html.contains("&lt;script&gt;"),
          html.contains("alert(&quot;x&quot;)"),
          html.contains("Grok's Beard failed to load this view"),
          html.contains("Grok's Beard output channel"),
        )
      },
    )
end ChatHtmlSpec
