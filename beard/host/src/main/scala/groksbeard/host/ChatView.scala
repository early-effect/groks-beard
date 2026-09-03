package groksbeard.host

import groksbeard.core.{ChatHtml, HostMsg, WebviewMsg}
import groksbeard.host.vscode.*
import zio.json.*

import scala.scalajs.js

final class ChatView(context: ExtensionContext) extends WebviewViewProvider:
  def resolveWebviewView(
      webviewView: WebviewView,
      ctx: WebviewViewResolveContext,
      token: CancellationToken,
  ): Unit =
    val webview = webviewView.webview
    webview.options = WebviewOptions(
      enableScripts = true,
      localResourceRoots = js.Array(context.extensionUri),
    )
    val scriptUri = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, "dist", "webview", "chat.js"))
    val logoUri   = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, "media", "logo.png"))
    webview.html = ChatHtml.page(
      cspSource = webview.cspSource,
      scriptUri = scriptUri.asString,
      logoUri = Some(logoUri.asString),
      ctrlEnterToSend = false,
    )
    webview.onDidReceiveMessage { raw =>
      val json = js.JSON.stringify(raw)
      json.fromJson[WebviewMsg].foreach {
        case WebviewMsg.Ready =>
          val ready: HostMsg = HostMsg.Ready
          val meta: HostMsg  = HostMsg.SessionMeta("local", "Grok's Beard", "normal")
          val _              = webview.postMessage(js.JSON.parse(ready.toJson))
          val _              = webview.postMessage(js.JSON.parse(meta.toJson))
        case WebviewMsg.Send(_) =>
          ()
      }
    }
    ()
  end resolveWebviewView
end ChatView
