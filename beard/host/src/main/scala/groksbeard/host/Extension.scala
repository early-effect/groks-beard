package groksbeard.host

import groksbeard.host.vscode.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

object Extension:
  private val ChatViewId          = "groksBeard.chat"
  private val ChatViewIdSecondary = "groksBeard.chatSecondary"

  @JSExportTopLevel("activate")
  def activate(context: ExtensionContext): Unit =
    val docs   = new BeardDocs
    val review = new Review(docs)
    val status = vscode.window.createStatusBarItem(2, 80)
    status.command = "groksBeard.openChangesReview"
    status.text = "$(diff) Grok Changes"
    var chatRef: Option[ChatView] = None
    val tree = new ChangesTree(() => chatRef.toList.flatMap(_.current.toList.flatMap(_.pendingChanges)))
    val chat = new ChatView(context, review, tree, status)
    chatRef = Some(chat)
    val retain         = WebviewViewProviderOptions(WebviewPanelOptions(retainContextWhenHidden = true))
    val useActivityBar =
      vscode.env.appName != "Visual Studio Code" && vscode.env.appName != "VS Code"
    val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.useActivityBar", useActivityBar)
    context.subscriptions.push(
      vscode.workspace.registerTextDocumentContentProvider(BeardDocs.Original, docs)
    )
    context.subscriptions.push(
      vscode.workspace.registerTextDocumentContentProvider(BeardDocs.Proposed, docs)
    )
    context.subscriptions.push(vscode.window.registerTreeDataProvider("groksBeard.changes", tree))
    context.subscriptions.push(vscode.window.registerTreeDataProvider("groksBeard.changesSecondary", tree))
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
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.openChangesReview", () => chat.current.foreach(_.openChanges()))
    )
    context.subscriptions.push(
      vscode.commands.registerCommand(
        "groksBeard.openDiff",
        (arg: js.Any) =>
          val id = Extension.asString(arg)
          if id.nonEmpty then chat.current.foreach(_.openDiff(id))
          else chat.current.foreach(_.openChanges()),
      )
    )
    context.subscriptions.push(
      vscode.commands.registerCommand(
        "groksBeard.keepChange",
        (arg: js.Any) =>
          val path = Extension.asString(arg)
          if path.nonEmpty then chat.current.foreach(_.keep(path)),
      )
    )
    context.subscriptions.push(
      vscode.commands.registerCommand(
        "groksBeard.undoChange",
        (arg: js.Any) =>
          val path = Extension.asString(arg)
          if path.nonEmpty then chat.current.foreach(_.undo(path)),
      )
    )
    ()
  end activate

  @JSExportTopLevel("deactivate")
  def deactivate(): Unit = ()

  private def asString(arg: js.Any): String =
    arg.asInstanceOf[js.UndefOr[String]].toOption.filter(s => s != null && s.nonEmpty).getOrElse("")
end Extension
