package groksbeard.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

@js.native
trait VsCodeApi extends js.Object:
  def postMessage(message: js.Any): Unit = js.native

/** VS Code injects `acquireVsCodeApi` on the webview `window`. Preview has no such method. */
@js.native
trait WebviewWindow extends js.Object:
  def acquireVsCodeApi(): VsCodeApi = js.native

@js.native
@JSGlobalScope
object GlobalThis extends js.Object:
  val window: WebviewWindow = js.native

object VsCodeApi:
  def current: Option[VsCodeApi] =
    val win = GlobalThis.window
    if !js.Object.hasProperty(win, "acquireVsCodeApi") then None
    else Some(win.acquireVsCodeApi())
