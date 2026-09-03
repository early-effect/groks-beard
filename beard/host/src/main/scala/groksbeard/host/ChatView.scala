package groksbeard.host

import groksbeard.core.{ChatHtml, HostMsg, ModeOption, SettingsState, SlashCommand, WebviewMsg}
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
      val json                     = js.JSON.stringify(raw)
      def post(msg: HostMsg): Unit =
        val _ = webview.postMessage(js.JSON.parse(msg.toJson))
        ()

      json.fromJson[WebviewMsg].foreach {
        case WebviewMsg.Ready =>
          post(HostMsg.Ready)
          post(
            HostMsg.SessionMeta(
              "local",
              "Grok's Beard",
              "normal",
              List(
                ModeOption("normal", "Normal"),
                ModeOption("plan", "Plan"),
                ModeOption("auto", "Auto"),
                ModeOption("always-approve", "Always approve"),
              ),
            )
          )
          post(
            HostMsg.AvailableCommands(
              List(
                SlashCommand("compact", "Compact context"),
                SlashCommand("always-approve", "Skip permission prompts"),
              )
            )
          )
          post(HostMsg.Settings(SettingsState.defaults))
        case WebviewMsg.MentionQuery(query) =>
          post(HostMsg.MentionResults(query, Nil))
        case WebviewMsg.SetMode(id) =>
          post(HostMsg.SessionMeta("local", "Grok's Beard", id, Nil))
        case WebviewMsg.OpenSettings =>
          post(HostMsg.Settings(SettingsState.defaults))
        case _ =>
          ()
      }
    }
    ()
  end resolveWebviewView
end ChatView
