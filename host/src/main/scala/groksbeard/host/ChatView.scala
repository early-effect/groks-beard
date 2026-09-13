package groksbeard.host

import groksbeard.core.*
import groksbeard.host.vscode.*
import zio.*
import zio.json.*

import scala.scalajs.js

final class ChatView(
    context: ExtensionContext,
    review: Review,
    tree: ChangesTree,
    status: StatusBarItem,
    out: BeardOut,
    rememberSelection: PromptChip => Unit = _ => (),
) extends WebviewViewProvider:

  private var runtime: Option[ChatRuntime]  = None
  private var missingCli: Option[String]    = None
  private var pendingReady: Boolean         = false
  private var toHost: HostMsg => UIO[Unit]  = _ => ZIO.unit
  private var spawnedShare: Option[Boolean] = None
  private val spawnGen                      = new java.util.concurrent.atomic.AtomicInteger(0)
  private val resolveN                      = new java.util.concurrent.atomic.AtomicInteger(0)
  private val grokGate = new java.util.concurrent.atomic.AtomicReference[Promise[Nothing, ChatRuntime]](null)
  private val sinks    = new java.util.concurrent.atomic.AtomicReference(List.empty[HostMsg => UIO[Unit]])

  def current: Option[ChatRuntime] = runtime

  def toggleTodos(): Unit = HostRuntime.runUIO(toHost(HostMsg.ToggleTodos))

  def toggleTasks(): Unit = HostRuntime.runUIO(toHost(HostMsg.ToggleTasks))

  def toggleQueue(): Unit = HostRuntime.runUIO(toHost(HostMsg.ToggleQueue))

  def openPalette(): Unit = HostRuntime.runUIO(toHost(HostMsg.OpenPalette))

  def openMcps(): Unit =
    HostRuntime.runUIO {
      toHost(HostMsg.OpenMcps) *>
        runtime.map(_.listMcps).getOrElse(ZIO.unit)
    }

  def dispose(): Unit =
    runtime.foreach(rt => HostRuntime.runUIO(rt.close))
    runtime = None

  def resolveWebviewView(
      webviewView: WebviewView,
      ctx: WebviewViewResolveContext,
      token: CancellationToken,
  ): Unit =
    val webview = webviewView.webview
    try resolve(webview)
    catch
      case e: Throwable =>
        val text = out.error("resolveWebviewView", e)
        try webview.html = ChatHtml.errorPage(text)
        catch case _: Throwable => ()
  end resolveWebviewView

  private def resolve(webview: Webview): Unit =
    webview.options = WebviewOptions(
      enableScripts = true,
      localResourceRoots = js.Array(context.extensionUri),
    )
    out.line(s"resolving chat webview #${resolveN.incrementAndGet()}")
    val scriptUri = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, "dist", "webview", "chat.js"))
    val logoUri   = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, "media", "logo.png"))
    webview.html = ChatHtml.page(
      cspSource = webview.cspSource,
      scriptUri = scriptUri.asString(),
      logoUri = Some(logoUri.asString()),
      ctrlEnterToSend = readSettings().useCtrlEnterToSend,
    )
    def post(msg: HostMsg): UIO[Unit] =
      ZIO.succeed {
        val _ = webview.postMessage(js.JSON.parse(msg.toJson))
        msg match
          case HostMsg.Changes(fileCount, additions, deletions, _) =>
            status.text =
              if fileCount == 0 then "$(beard) Grok"
              else s"$$(diff) $fileCount  +$additions/-$deletions"
            if fileCount > 0 then status.show() else status.hide()
            val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.hasChanges", fileCount > 0)
          case HostMsg.UserMessage(_, _, _, _) =>
            val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", true)
          case HostMsg.TurnEnd(_, _) =>
            val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", false)
          case HostMsg.ClearTranscript =>
            val _ = vscode.commands.executeCommand[js.Any]("setContext", "groksBeard.turnRunning", false)
          case _ => ()
        end match
        ()
      }
    addSink(post)
    toHost = broadcast
    bindAgent()
    webview.onDidReceiveMessage { raw =>
      Wire.webview(js.JSON.stringify(raw)) match
        case Left(err) =>
          out.line(s"[error] webview decode: $err")
          HostRuntime.runUIO(ZIO.logError(err) *> post(HostMsg.Error(err, Some(Wire.Decode))))
        case Right(msg) =>
          handleWebview(msg, post)
    }
    ()
  end resolve

  private def handleWebview(msg: WebviewMsg, post: HostMsg => UIO[Unit]): Unit =
    msg match
      case WebviewMsg.Log(message, level) =>
        out.line(s"[$level] $message")
      case WebviewMsg.Ready =>
        out.line("webview ready")
      case _ => ()
    runtime match
      case Some(rt) =>
        msg match
          case WebviewMsg.Log(_, _)        => ()
          case WebviewMsg.MentionQuery(q)  => searchMentions(q, post)
          case WebviewMsg.AddSelection     => addSelection()
          case WebviewMsg.SetSetting(k, v) =>
            writeSetting(k, v)
            HostRuntime.runUIO(HostDispatch(rt, msg, post))
            k match
              case SettingKey.ShareBackend =>
                v match
                  case b: Boolean => rebindIfShareChanged(b)
                  case _          => ()
              case _ => ()
          case _ => HostRuntime.runUIO(HostDispatch(rt, msg, post))
      case None =>
        missingCli match
          case Some(err) =>
            msg match
              case WebviewMsg.Ready =>
                HostRuntime.runUIO(post(HostMsg.Ready) *> post(HostMsg.Error(err)))
              case WebviewMsg.Log(_, _) => ()
              case _                    => HostRuntime.runUIO(post(HostMsg.Error(err)))
          case None =>
            if msg == WebviewMsg.Ready then pendingReady = true
    end match
  end handleWebview

  private def broadcast(msg: HostMsg): UIO[Unit] =
    ZIO.foreachDiscard(sinks.get())(_(msg))

  private def addSink(post: HostMsg => UIO[Unit]): Unit =
    var cur = sinks.get()
    while !sinks.compareAndSet(cur, post :: cur) do cur = sinks.get()

  private def bindAgent(): Unit =
    HostRuntime.runScoped(ensureGrok)

  private def ensureGrok: ZIO[Scope, Nothing, Unit] =
    for
      p    <- Promise.make[Nothing, ChatRuntime]
      mine <- ZIO.succeed(grokGate.compareAndSet(null, p))
      _    <- if mine then ZIO.log("starting grok") else ZIO.log("reuse grok")
      rt   <- if mine then startGrok(p) else grokGate.get().await
      _    <- ZIO.succeed { runtime = Some(rt) }
      _    <- flushReady(rt)
    yield ()

  private def startGrok(p: Promise[Nothing, ChatRuntime]): ZIO[Scope, Nothing, ChatRuntime] =
    spawnPipeline
      .tap(rt => p.succeed(rt))
      .catchAll: e =>
        ZIO.logError(e.message) *>
          ZIO.succeed:
            missingCli = Some(e.message)
            grokGate.set(null)
            out.line(e.message)
          *> p.die(RuntimeException(e.message)) *>
          ZIO.dieMessage(e.message)

  private def spawnPipeline: ZIO[Scope, BeardError, ChatRuntime] =
    val cwd     = vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath).getOrElse(".")
    val cliPath =
      vscode.workspace.getConfiguration("groksBeard").get[String](SettingKey.CliPath.wire).toOption.filter(_.nonEmpty)
    val env   = (k: String) => nodeProcess.env.get(k).flatMap(_.toOption)
    val win   = nodeProcess.platform == "win32"
    val share = readSettings().shareBackend
    val cmd   = CliLocator.locate(LocateGrok(cliPath, env, win, nodeFs.existsSync))
    val note  = new java.util.concurrent.atomic.AtomicReference[String => UIO[Unit]](_ => ZIO.unit)
    val gone  = new java.util.concurrent.atomic.AtomicReference[UIO[Unit]](ZIO.unit)
    val gen   = spawnGen.incrementAndGet()
    val disk  = new ChangeDisk(context)
    val home  = GrokHome(env)
    val caps  = ClientCapabilities.forSpawn(None, verified = false, terminalHandlersReady = true)
    for
      bin <- ZIO.fromEither(cmd).mapError(searched => BeardError.DecodeError(Onboarding.missingCliMessage(searched)))
      args = Spawn.grokAgentStdioArgs(shareBackend = share)
      _    = spawnedShare = Some(share)
      _    <- ZIO.log(s"spawn $bin ${args.mkString(" ")} cwd=$cwd share=$share")
      pair <-
        NodeTransport.spawn(
          bin,
          args,
          cwd,
          out.line,
          onErr = line => note.get()(line),
          onExit = _ => if spawnGen.get() == gen then gone.get() else ZIO.unit,
          run = HostRuntime.runUIO,
        ) zipPar disk.load.orElseSucceed(Nil)
      (transport, saved) = pair
      _  <- ZIO.log("stdio up")
      rt <- ChatRuntime
        .make(
          transport,
          cwd,
          caps,
          activeFile = () => activeFileChip(),
          includeActiveFile = () => readSettings().includeActiveFileByDefault,
          settings = () => readSettings(),
          beforeInitialize = NodeMcp.awaitIn(cwd, broadcast, out.line),
        )
        .provideSome[Scope](agentEnv(bin, cwd, home, env, disk))
      _ <- ZIO.succeed:
        note.set(rt.noteAgentLine)
        gone.set(rt.noteAgentGone)
      _ <- ZIO.log("runtime up")
      _ <- rt.restoreChanges(saved)
    yield rt
    end for
  end spawnPipeline

  private def agentEnv(
      bin: String,
      cwd: String,
      home: String,
      env: String => Option[String],
      disk: ChangeDisk,
  ): ZLayer[Scope, Nothing, ChatEnv.Env] =
    (HostOut.layer(broadcast) ++
      SessionRepo.of(NodeSessionFs, home, cwd) ++
      Mentions.none ++
      ChangesPersist.layer(disk.save, disk.load) ++
      TranscriptOut.of(
        NodeSessionFs,
        home,
        cwd,
        env,
        text => ZIO.succeed { val _ = vscode.env.clipboard.writeText(text) },
      ) ++
      ReviewOps.layer(
        read = _ => ZIO.none,
        openDiffs = (heading, diffs) => ZIO.succeed(review.open(heading, diffs)),
        undo = review.applyUndo,
        storeChanged = ZIO.succeed(tree.refresh()),
        onFollow = (path, line) => ZIO.succeed(review.follow(path, line)),
        onOpenText = path => ZIO.succeed(review.openText(path)),
      ) ++
      NodeTerminals.layer(cwd) ++
      ZLayer.succeed(
        Mcps.cli(
          args => NodeCapture.run(bin, args, cwd),
          ZIO.attempt(nodeFs.readFileSync(s"$home/config.toml", "utf8")).orElseSucceed(""),
        )
      )) >+> UiPrefs.layer

  private def flushReady(rt: ChatRuntime): UIO[Unit] =
    if !pendingReady then ZIO.unit
    else
      ZIO.succeed { pendingReady = false } *>
        ZIO.log("flush webview ready") *>
        HostDispatch(rt, WebviewMsg.Ready, broadcast)

  def refreshSettings(): Unit =
    val next = readSettings()
    runtime.foreach(rt => HostRuntime.runUIO(rt.replaceSettings(next)))
    rebindIfShareChanged(next.shareBackend)

  private def rebindIfShareChanged(share: Boolean): Unit =
    if spawnedShare.contains(share) then ()
    else
      spawnGen.incrementAndGet()
      grokGate.set(null)
      runtime.foreach(rt => HostRuntime.runUIO(rt.close))
      runtime = None
      pendingReady = true
      bindAgent()

  def addSelection(): Unit =
    activeSelectionChip() match
      case None =>
        val _ = vscode.window.showWarningMessage("No selection to add to chat.")
      case Some(chip) =>
        rememberSelection(chip)
        runtime.foreach(rt => HostRuntime.runUIO(rt.addChip(chip)))
        focusChat()

  def addFile(): Unit =
    activeFileChip() match
      case None =>
        val _ = vscode.window.showWarningMessage("No active editor.")
      case Some(chip) =>
        runtime.foreach(rt => HostRuntime.runUIO(rt.addChip(chip.copy(source = ChipSource.File))))
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
      cliPath = cfg.get[String](SettingKey.CliPath.wire).toOption.getOrElse(""),
      nodePath = cfg.get[String](SettingKey.NodePath.wire).toOption.getOrElse(""),
      includeActiveFileByDefault = cfg.get[Boolean](SettingKey.IncludeActiveFile.wire).toOption.getOrElse(true),
      useCtrlEnterToSend = cfg.get[Boolean](SettingKey.UseCtrlEnterToSend.wire).toOption.getOrElse(false),
      changesPresentation = cfg.get[String](SettingKey.ChangesPresentation.wire).toOption.getOrElse("toast"),
      shareBackend = cfg.get[Boolean](SettingKey.ShareBackend.wire).toOption.getOrElse(true),
    )
  end readSettings

  private def writeSetting(key: SettingKey, value: String | Boolean): Unit =
    val cfg = vscode.workspace.getConfiguration("groksBeard")
    if key.flag then
      value match
        case b: Boolean =>
          val _ = cfg.update(key.wire, b, ConfigurationTarget.Global)
        case _ => ()
    else
      value match
        case s: String =>
          val _ = cfg.update(key.wire, s, ConfigurationTarget.Global)
        case _ => ()
    end if
  end writeSetting

  private def workspaceRoot: Option[String] =
    vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath)

  private def activeFileChip(): Option[PromptChip] =
    vscode.window.activeTextEditor.toOption.map { ed =>
      PromptChip.fromFile(
        ed.document.uri.fsPath,
        workspaceRoot,
        languageId = Some(ed.document.languageId),
        source = ChipSource.Active,
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

  private def searchMentions(query: String, post: HostMsg => UIO[Unit]): Unit =
    MentionSearch.pattern(query) match
      case None          => HostRuntime.runUIO(post(HostMsg.MentionResults(query, Nil)))
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
            HostRuntime.runUIO(post(HostMsg.MentionResults(query, MentionSearch.rank(files, query))))
            js.undefined
          }
end ChatView
