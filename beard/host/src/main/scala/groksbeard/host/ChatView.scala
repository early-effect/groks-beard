package groksbeard.host

import groksbeard.core.{ChatHtml, ChatRuntime, HostMsg, SettingsState, WebviewMsg}
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
    def post(msg: HostMsg): Unit =
      val _ = webview.postMessage(js.JSON.parse(msg.toJson))
      ()
    val runtime = ChatRuntime(post)
    webview.onDidReceiveMessage { raw =>
      js.JSON.stringify(raw).fromJson[WebviewMsg].foreach {
        case WebviewMsg.Ready               => runtime.ready()
        case WebviewMsg.Send(text)          => runtime.send(text)
        case WebviewMsg.Queue(text)         => runtime.queue(text)
        case WebviewMsg.Cancel              => runtime.cancel()
        case WebviewMsg.SetMode(id)         => runtime.setMode(id)
        case WebviewMsg.MentionQuery(query) => post(HostMsg.MentionResults(query, Nil))
        case WebviewMsg.OpenSettings        => post(HostMsg.Settings(SettingsState.defaults))
        case _                              => ()
      }
    }
    ()
  end resolveWebviewView
end ChatView
