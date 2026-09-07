package groksbeard.core

/** Webview HTML shell. CSP must not include `unsafe-eval`. */
object ChatHtml:
  def page(cspSource: String, scriptUri: String, logoUri: Option[String], ctrlEnterToSend: Boolean): String =
    val csp = List(
      "default-src 'none'",
      s"script-src $cspSource",
      s"style-src $cspSource 'unsafe-inline'",
      s"img-src $cspSource data:",
      "connect-src 'none'",
    ).mkString("; ")
    val logo = logoUri.filter(_.nonEmpty).map(uri => s""" data-logo="${escape(uri)}"""").getOrElse("")
    s"""<!DOCTYPE html>
<html lang="en"$logo>
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="$csp" />
</head>
<body data-ctrl-enter="${if ctrlEnterToSend then "true" else "false"}"$logo>
  <div id="root"></div>
  <script src="${escape(scriptUri)}"></script>
</body>
</html>
"""
  end page

  def errorPage(message: String): String =
    s"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <style>
    html, body { background: var(--vscode-editor-background, #1e1e1e); color: var(--vscode-errorForeground, #f48771); font: 13px/1.4 var(--vscode-font-family, sans-serif); margin: 0; padding: 12px; }
    pre { white-space: pre-wrap; word-break: break-word; }
  </style>
</head>
<body>
  <p>Grok's Beard failed to load this view.</p>
  <pre>${escape(message)}</pre>
  <p>The same error is in the Grok's Beard output channel.</p>
</body>
</html>
"""

  def hasUnsafeEval(html: String): Boolean =
    html.contains("unsafe-eval")

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
end ChatHtml
