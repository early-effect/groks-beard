package groksbeard.host.vscode

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait Disposable extends js.Object:
  def dispose(): Unit = js.native

@js.native
trait Uri extends js.Object:
  def fsPath: String = js.native
  @js.annotation.JSName("toString")
  def asString: String = js.native

@js.native
trait UriNs extends js.Object:
  def file(path: String): Uri                         = js.native
  def joinPath(base: Uri, pathSegments: String*): Uri = js.native

@js.native
trait ExtensionContext extends js.Object:
  val subscriptions: js.Array[Disposable] = js.native
  val extensionUri: Uri                   = js.native

/** Options we send into a webview. Not `@js.native`: we construct these in Scala. */
class WebviewOptions(
    val enableScripts: Boolean,
    val localResourceRoots: js.Array[Uri],
) extends js.Object

class WebviewPanelOptions(
    val retainContextWhenHidden: Boolean
) extends js.Object

class WebviewViewProviderOptions(
    val webviewOptions: WebviewPanelOptions
) extends js.Object

@js.native
trait Webview extends js.Object:
  var html: String                                                         = js.native
  var options: WebviewOptions                                              = js.native
  def cspSource: String                                                    = js.native
  def asWebviewUri(uri: Uri): Uri                                          = js.native
  def postMessage(message: js.Any): js.Promise[Boolean]                    = js.native
  def onDidReceiveMessage(listener: js.Function1[js.Any, Any]): Disposable = js.native

@js.native
trait WebviewView extends js.Object:
  def webview: Webview = js.native

@js.native
trait WebviewViewResolveContext extends js.Object

@js.native
trait CancellationToken extends js.Object

/** Implemented in Scala; VS Code calls `resolveWebviewView`. */
trait WebviewViewProvider extends js.Object:
  def resolveWebviewView(
      webviewView: WebviewView,
      context: WebviewViewResolveContext,
      token: CancellationToken,
  ): Unit

@js.native
trait WindowNs extends js.Object:
  def registerWebviewViewProvider(
      viewId: String,
      provider: WebviewViewProvider,
      options: js.UndefOr[WebviewViewProviderOptions] = js.undefined,
  ): Disposable                                                         = js.native
  def showErrorMessage(message: String): js.Promise[js.Any]             = js.native
  def showInformationMessage(message: String): js.Promise[js.Any]       = js.native
  def createOutputChannel(name: String): OutputChannel                  = js.native
  def createStatusBarItem(alignment: Int, priority: Int): StatusBarItem = js.native
end WindowNs

@js.native
trait OutputChannel extends js.Object:
  def appendLine(value: String): Unit = js.native

@js.native
trait StatusBarItem extends js.Object:
  var text: String    = js.native
  var command: String = js.native
  def show(): Unit    = js.native
  def hide(): Unit    = js.native

@js.native
trait CommandsNs extends js.Object:
  def registerCommand(command: String, callback: js.Function0[Any]): Disposable = js.native
  def executeCommand[T](command: String, rest: js.Any*): js.Promise[T]          = js.native

@js.native
trait WorkspaceConfiguration extends js.Object:
  def get[T](section: String): js.UndefOr[T] = js.native

@js.native
trait WorkspaceFolder extends js.Object:
  def uri: Uri = js.native

@js.native
trait WorkspaceNs extends js.Object:
  def workspaceFolders: js.UndefOr[js.Array[WorkspaceFolder]]                   = js.native
  def getConfiguration(section: String): WorkspaceConfiguration                 = js.native
  def onDidChangeConfiguration(listener: js.Function1[js.Any, Any]): Disposable = js.native

@js.native
trait EnvNs extends js.Object:
  def appName: String = js.native

@js.native
@JSImport("vscode", JSImport.Namespace)
object vscode extends js.Object:
  val window: WindowNs       = js.native
  val commands: CommandsNs   = js.native
  val workspace: WorkspaceNs = js.native
  val env: EnvNs             = js.native
  val Uri: UriNs             = js.native
