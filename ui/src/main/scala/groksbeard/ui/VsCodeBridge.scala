package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg}
import groksbeard.facade.VsCodeApi
import zio.json.*

import scala.scalajs.js

final class VsCodeBridge(api: VsCodeApi) extends HostBridge:
  def post(msg: WebviewMsg): Unit =
    api.postMessage(js.JSON.parse(msg.toJson))

  def onHost(f: HostMsg => Unit): Unit =
    ascent.dom.window.addEventListener(
      "message",
      (event: ascent.dom.Event) =>
        val data = event.asInstanceOf[ascent.dom.MessageEvent].data
        val raw  = js.JSON.stringify(data)
        raw.fromJson[HostMsg].foreach(f),
    )
end VsCodeBridge
