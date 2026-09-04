package groksbeard.host

import groksbeard.core.{ChatHtml, ChatRuntime, HostMsg, ReviewPorts, SettingsState, WebviewMsg}
import groksbeard.host.vscode.*
import zio.json.*

import scala.scalajs.js

final class ChatView(context: ExtensionContext, review: Review, tree: ChangesTree, status: StatusBarItem)
    extends WebviewViewProvider:

  private var runtime: Option[ChatRuntime] = None

  def current: Option[ChatRuntime] = runtime
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
      msg match
        case HostMsg.Changes(fileCount, additions, deletions, _) =>
          status.text =
            if fileCount == 0 then "$(beard) Grok"
            else s"$$(diff) $fileCount  +$additions/-$deletions"
          if fileCount > 0 then status.show() else status.hide()
        case _ => ()
      ()
    end post
    val rt = ChatRuntime(
      post,
      ports = ReviewPorts(
        readDisk = review.readDisk,
        openNativeDiffs = review.open,
        applyUndo = review.applyUndo,
        onStoreChange = () => tree.refresh(),
      ),
    )
    runtime = Some(rt)
    webview.onDidReceiveMessage { raw =>
      js.JSON.stringify(raw).fromJson[WebviewMsg].foreach {
        case WebviewMsg.Ready               => rt.ready()
        case WebviewMsg.Send(text)          => rt.send(text)
        case WebviewMsg.Queue(text)         => rt.queue(text)
        case WebviewMsg.Cancel              => rt.cancel()
        case WebviewMsg.SetMode(id)         => rt.setMode(id)
        case WebviewMsg.MentionQuery(query) => post(HostMsg.MentionResults(query, Nil))
        case WebviewMsg.OpenSettings        => post(HostMsg.settings(SettingsState.defaults))
        case WebviewMsg.OpenDiff(id)        => rt.openDiff(id)
        case WebviewMsg.OpenChanges         => rt.openChanges()
        case WebviewMsg.KeepChange(path)    => rt.keep(path)
        case WebviewMsg.UndoChange(path)    => rt.undo(path)
        case WebviewMsg.CloseDiff           => rt.closeDiff()
        case _                              => ()
      }
    }
    ()
  end resolveWebviewView
end ChatView
