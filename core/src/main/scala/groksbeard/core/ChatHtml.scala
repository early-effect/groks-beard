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

  def hasUnsafeEval(html: String): Boolean =
    html.contains("unsafe-eval")

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
end ChatHtml
