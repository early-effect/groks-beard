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
    rememberSelection: PromptChip => Unit = _ => (),
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
      ctrlEnterToSend = readSettings().useCtrlEnterToSend,
    )
    def post(msg: HostMsg): Unit =
      val _ = webview.postMessage(js.JSON.parse(msg.toJson))
      msg match
        case HostMsg.Changes(fileCount, additions, deletions, _) =>
          status.text =
            if fileCount == 0 then "$(beard) Grok"
            else s"$$(diff) $fileCount  +$additions/-$deletions"
          if fileCount > 0 then status.show() else status.hide()
        case HostMsg.UserMessage(_, _, _, _) =>
          val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", true)
        case HostMsg.TurnEnd(_, _) =>
          val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", false)
        case HostMsg.ClearTranscript =>
          val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", false)
        case _ => ()
      end match
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
          case Some(rt) =>
            msg match
              case WebviewMsg.MentionQuery(q)        => searchMentions(q, post)
              case WebviewMsg.AddSelection           => addSelection()
              case WebviewMsg.SetSetting(key, value) =>
                writeSetting(key, value)
                HostDispatch(rt, msg, post)
              case other => HostDispatch(rt, other, post)
          case None =>
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
        var note: String => Unit = _ => ()
        val transport            = NodeTransport.spawn(cmd, args, cwd, log, line => note(line))
        val caps                 = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = false)
        val home                 = GrokHome(env)
        val rt                   = ChatRuntime(
          post,
          transport,
          ports,
          cwd,
          caps,
          activeFile = () => activeFileChip(),
          includeActiveFile = () => readSettings().includeActiveFileByDefault,
          settings = () => readSettings(),
          listSessions = () => SessionIndex.listRows(NodeSessionFs, home, cwd),
          scheduleEmptyDelete = id =>
            val path = SessionIndex.sessionPath(home, cwd, id)
            val _    = js.timers.setTimeout(SessionIndex.EmptyGraceMs.toDouble) {
              NodeSessionFs.deleteTree(path)
            }
          ,
          renameOnDisk = (id, op) => SessionEdit.rename(NodeSessionFs, home, cwd, id, op),
          deleteOnDisk = id => SessionEdit.delete(NodeSessionFs, home, cwd, id),
        )
        note = rt.noteAgentLine
        runtime = Some(rt)
    end match
  end bindAgent

  def refreshSettings(): Unit =
    runtime.foreach(_.replaceSettings(readSettings()))

  def addSelection(): Unit =
    activeSelectionChip() match
      case None =>
        val _ = vscode.window.showWarningMessage("No selection to add to chat.")
      case Some(chip) =>
        rememberSelection(chip)
        runtime.foreach(_.addChip(chip))
        focusChat()

  def addFile(): Unit =
    activeFileChip() match
      case None =>
        val _ = vscode.window.showWarningMessage("No active editor.")
      case Some(chip) =>
        runtime.foreach(_.addChip(chip.copy(source = "file")))
        val _ = vscode.window.showInformationMessage(s"Added ${PromptChip.formatAtRef(chip)}")

  def copySelectionAsGrokRef(): Unit =
    activeSelectionChip().orElse(activeFileChip()) match
      case None =>
        val _ = vscode.window.showWarningMessage("No active editor.")
      case Some(chip) =>
        rememberSelection(chip)
        val _ = vscode.env.clipboard.writeText(PromptChip.formatAtRef(chip))

  private def focusChat(): Unit =
    val viewId =
      if vscode.env.appName == "Visual Studio Code" || vscode.env.appName == "VS Code"
      then "groksBeard.chatSecondary"
      else "groksBeard.chat"
    val _ = vscode.commands.executeCommand[js.Any](s"$viewId.focus")

  private def readSettings(): SettingsState =
    val cfg = vscode.workspace.getConfiguration("groksBeard")
    SettingsState(
      cliPath = cfg.get[String]("cliPath").toOption.getOrElse(""),
      nodePath = cfg.get[String]("nodePath").toOption.getOrElse(""),
      includeActiveFileByDefault = cfg.get[Boolean]("includeActiveFileByDefault").toOption.getOrElse(true),
      useCtrlEnterToSend = cfg.get[Boolean]("useCtrlEnterToSend").toOption.getOrElse(false),
      changesPresentation = cfg.get[String]("changesPresentation").toOption.getOrElse("toast"),
    )

  private def writeSetting(key: String, value: String | Boolean): Unit =
    val cfg = vscode.workspace.getConfiguration("groksBeard")
    key match
      case "includeActiveFileByDefault" | "useCtrlEnterToSend" =>
        value match
          case b: Boolean =>
            val _ = cfg.update(key, b, ConfigurationTarget.Global)
          case _ => ()
      case "cliPath" | "nodePath" | "changesPresentation" =>
        value match
          case s: String =>
            val _ = cfg.update(key, s, ConfigurationTarget.Global)
          case _ => ()
      case _ => ()
    end match
  end writeSetting

  private def workspaceRoot: Option[String] =
    vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath)

  private def activeFileChip(): Option[PromptChip] =
    vscode.window.activeTextEditor.toOption.map { ed =>
      PromptChip.fromFile(
        ed.document.uri.fsPath,
        workspaceRoot,
        languageId = Some(ed.document.languageId),
        source = "active",
      )
    }

  private def activeSelectionChip(): Option[PromptChip] =
    vscode.window.activeTextEditor.toOption.filter(ed => !ed.selection.isEmpty).map { ed =>
      val sel = ed.selection
      PromptChip.fromSelection(
        ed.document.uri.fsPath,
        workspaceRoot,
        startLine = Some(sel.start.line + 1),
        endLine = Some(sel.end.line + 1),
        languageId = Some(ed.document.languageId),
        excerpt = Some(ed.document.getText(sel)),
      )
    }

  private def searchMentions(query: String, post: HostMsg => Unit): Unit =
    MentionSearch.pattern(query) match
      case None          => post(HostMsg.MentionResults(query, Nil))
      case Some(pattern) =>
        val _ = vscode.workspace
          .findFiles(pattern, MentionSearch.ExcludeGlob, MentionSearch.FileLimit)
          .`then` { (uris: js.Array[Uri]) =>
            val files = uris.toList.map { uri =>
              val abs = uri.fsPath
              val rel = workspaceRoot
                .filter(r => abs == r || abs.startsWith(r + "/") || abs.startsWith(r + "\\"))
                .map(r => abs.substring(r.length).replace('\\', '/').replaceAll("^/+", ""))
                .getOrElse(abs)
              MentionFile(rel, abs)
            }
            post(HostMsg.MentionResults(query, MentionSearch.rank(files, query)))
            js.undefined
          }
end ChatView
