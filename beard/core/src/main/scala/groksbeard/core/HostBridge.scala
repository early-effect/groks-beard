package groksbeard.core

/** UI talks to a host only through this. Preview and the VS Code webview are two implementations. */
trait HostBridge:
  def post(msg: WebviewMsg): Unit
  def onHost(f: HostMsg => Unit): Unit
