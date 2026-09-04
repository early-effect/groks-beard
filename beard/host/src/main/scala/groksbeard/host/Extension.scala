package groksbeard.host

import groksbeard.core.*
import groksbeard.host.vscode.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

object Extension:
  private val ChatViewId                   = "groksBeard.chat"
  private val ChatViewIdSecondary          = "groksBeard.chatSecondary"
  private var activeChat: Option[ChatView] = None

  @JSExportTopLevel("activate")
  def activate(context: ExtensionContext): Unit =
    val docs   = new BeardDocs
    val review = new Review(docs)
    val status = vscode.window.createStatusBarItem(2, 80)
    status.command = "groksBeard.openChangesReview"
    status.text = "$(diff) Grok Changes"
    val out                          = vscode.window.createOutputChannel("Grok's Beard")
    var chatRef: Option[ChatView]    = None
    var treeRef: Option[ChangesTree] = None
    val mcpHost                      = new McpHost(review, () => treeRef.foreach(_.refresh()))
    val tree                         =
      new ChangesTree(() => chatRef.toList.flatMap(_.current.toList.flatMap(_.pendingChanges)) ++ mcpHost.sidecar)
    treeRef = Some(tree)
    val chat = new ChatView(context, review, tree, status, line => out.appendLine(line), mcpHost.rememberSelection)
    chatRef = Some(chat)
    activeChat = Some(chat)
    val bridge  = new TuiBridge(mcpHost.asToolHost, _ => ())
    val enabled =
      context.workspaceState.get[Boolean](TuiBridge.StateKey).toOption.contains(true)
    bridge.sync(enabled, mcpHost.workspaceFolder)
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
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.addSelection", () => chat.addSelection())
    )
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.addFile", () => chat.addFile())
    )
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.copySelectionAsGrokRef", () => chat.copySelectionAsGrokRef())
    )
    context.subscriptions.push(
      vscode.workspace.onDidChangeConfiguration(_ => chat.refreshSettings())
    )
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.enableTuiBridge", () => enableBridge(context, mcpHost, bridge))
    )
    context.subscriptions.push(
      vscode.commands.registerCommand("groksBeard.disableTuiBridge", () => disableBridge(context, mcpHost, bridge))
    )
    ()
  end activate

  @JSExportTopLevel("deactivate")
  def deactivate(): Unit =
    activeChat.foreach(_.dispose())
    activeChat = None

  private def enableBridge(context: ExtensionContext, mcpHost: McpHost, bridge: TuiBridge): Unit =
    val workspace = mcpHost.workspaceFolder
    if workspace.isEmpty then
      val _ = vscode.window.showErrorMessage("Open a workspace folder to enable the TUI bridge.")
    else
      val ws      = workspace.get
      val setting = vscode.workspace.getConfiguration("groksBeard").get[String]("nodePath").toOption.filter(_.nonEmpty)
      val pathEnv = nodeProcess.env
        .get("PATH")
        .flatMap(_.toOption)
        .orElse(nodeProcess.env.get("Path").flatMap(_.toOption))
        .getOrElse("")
      val located = NodeLocator.locate(
        LocateNode(setting, pathEnv, nodeProcess.platform == "win32", nodeFs.existsSync)
      )
      val proxy   = vscode.Uri.joinPath(context.extensionUri, "dist", "mcp-proxy.js").fsPath
      val nodeCmd = located.getOrElse("node")
      val snippet = McpToml.renderTable(nodeCmd, proxy, ws)
      val write   = "Write project .grok/config.toml"
      val copy    = "Copy snippet"
      located match
        case Left(searched) =>
          val _ = vscode.window
            .showWarningMessage(
              s"Node.js not found. Install Node or set groksBeard.nodePath. Looked in: ${searched.mkString(", ")}",
              copy,
            )
            .`then` { (pick: js.UndefOr[String]) =>
              if pick.toOption.contains(copy) then vscode.env.clipboard.writeText(snippet)
              js.undefined
            }
        case Right(_) =>
          val _ = vscode.window
            .showInformationMessage("Enable TUI Bridge for this workspace?", write, copy)
            .`then` { (pick: js.UndefOr[String]) =>
              pick.toOption match
                case Some(p) if p == write =>
                  val configPath = McpToml.projectConfigPath(ws)
                  nodeFs.mkdirSync(nodePath.dirname(configPath), js.Dynamic.literal(recursive = true))
                  val existing =
                    try nodeFs.readFileSync(configPath, "utf8")
                    catch case _: Throwable => ""
                  nodeFs.writeFileSync(configPath, McpToml.mergeTable(existing, snippet), "utf8")
                  val _ = vscode.window.showInformationMessage(McpToml.RefreshMessage)
                case Some(p) if p == copy =>
                  val _ = vscode.env.clipboard.writeText(snippet)
                case _ => ()
              end match
              js.undefined
            }
      end match
      val _ = context.workspaceState.update(TuiBridge.StateKey, true)
      bridge.sync(enabled = true, workspace)
    end if
  end enableBridge

  private def disableBridge(context: ExtensionContext, mcpHost: McpHost, bridge: TuiBridge): Unit =
    val _ = context.workspaceState.update(TuiBridge.StateKey, false)
    bridge.unbind()
    val remove = "Remove from project .grok/config.toml"
    val _ = vscode.window.showInformationMessage("TUI bridge disabled.", remove).`then` { (pick: js.UndefOr[String]) =>
      if pick.toOption.contains(remove) then
        mcpHost.workspaceFolder.foreach { ws =>
          val configPath = McpToml.projectConfigPath(ws)
          try
            val existing = nodeFs.readFileSync(configPath, "utf8")
            nodeFs.writeFileSync(configPath, McpToml.removeTable(existing), "utf8")
          catch case _: Throwable => ()
        }
      js.undefined
    }
  end disableBridge

  private def asString(arg: js.Any): String =
    arg.asInstanceOf[js.UndefOr[String]].toOption.filter(s => s != null && s.nonEmpty).getOrElse("")
end Extension
