package groksbeard.host

import groksbeard.host.vscode.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

object Extension:
  private val ChatViewId          = "groksBeard.chat"
  private val ChatViewIdSecondary = "groksBeard.chatSecondary"

  @JSExportTopLevel("activate")
  def activate(context: ExtensionContext): Unit =
    val chat           = new ChatView(context)
    val retain         = WebviewViewProviderOptions(WebviewPanelOptions(retainContextWhenHidden = true))
    val useActivityBar =
      vscode.env.appName != "Visual Studio Code" && vscode.env.appName != "VS Code"
    val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.useActivityBar", useActivityBar)
    context.subscriptions.push(
      vscode.window.registerWebviewViewProvider(ChatViewId, chat, retain)
    )
    context.subscriptions.push(
      vscode.window.registerWebviewViewProvider(ChatViewIdSecondary, chat, retain)
    )
    context.subscriptions.push(
      vscode.commands.registerCommand(
        "groksBeard.open",
        () =>
          val viewId =
            if vscode.env.appName == "Visual Studio Code" || vscode.env.appName == "VS Code"
            then ChatViewIdSecondary
            else ChatViewId
          vscode.commands.executeCommand[js.Any](s"$viewId.focus")
          (),
      )
    )
    ()
  end activate

  @JSExportTopLevel("deactivate")
  def deactivate(): Unit = ()
end Extension
