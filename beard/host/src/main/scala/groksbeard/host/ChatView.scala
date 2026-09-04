package groksbeard.host

import groksbeard.core.*
import groksbeard.host.vscode.*
import zio.json.*

import scala.scalajs.js

final class ChatView(
    context: ExtensionContext,
    review: Review,
    tree: ChangesTree,
    status: StatusBarItem,
    log: String => Unit,
) extends WebviewViewProvider:

  private var runtime: Option[ChatRuntime] = None
  private var missingCli: Option[String]   = None

  def current: Option[ChatRuntime] = runtime

  def dispose(): Unit =
    runtime.foreach(_.close())
    runtime = None

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
    val ports = ReviewPorts(
      readDisk = review.readDisk,
      openNativeDiffs = review.open,
      applyUndo = review.applyUndo,
      onStoreChange = () => tree.refresh(),
    )
    bindAgent(post, ports)
    webview.onDidReceiveMessage { raw =>
      js.JSON.stringify(raw).fromJson[WebviewMsg].foreach { msg =>
        runtime match
          case Some(rt) => HostDispatch(rt, msg, post)
          case None     =>
            val err = missingCli.getOrElse("Grok CLI not found.")
            msg match
              case WebviewMsg.Ready =>
                post(HostMsg.Ready)
                post(HostMsg.Error(err))
              case _ => post(HostMsg.Error(err))
      }
    }
    ()
  end resolveWebviewView

  private def bindAgent(post: HostMsg => Unit, ports: ReviewPorts): Unit =
    val cwd     = vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath).getOrElse(".")
    val cliPath = vscode.workspace.getConfiguration("groksBeard").get[String]("cliPath").toOption.filter(_.nonEmpty)
    val env     = (k: String) => nodeProcess.env.get(k).flatMap(_.toOption)
    val win     = nodeProcess.platform == "win32"
    CliLocator.locate(LocateGrok(cliPath, env, win, nodeFs.existsSync)) match
      case Left(searched) =>
        val err = Onboarding.missingCliMessage(searched)
        missingCli = Some(err)
        log(err)
      case Right(cmd) =>
        val args = Spawn.grokAgentStdioArgs()
        log(s"spawning $cmd ${args.mkString(" ")}")
        val transport = NodeTransport.spawn(cmd, args, cwd, log)
        val caps      = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = false)
        runtime = Some(ChatRuntime(post, transport, ports, cwd, caps))
    end match
  end bindAgent
end ChatView
