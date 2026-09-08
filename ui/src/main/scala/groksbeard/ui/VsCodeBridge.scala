package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg, Wire}
import groksbeard.facade.{Browser, VsCodeApi}
import zio.json.*

import scala.scalajs.js

final class VsCodeBridge(api: VsCodeApi) extends HostBridge:
  def post(msg: WebviewMsg): Unit =
    api.postMessage(js.JSON.parse(msg.toJson))

  def onHost(f: HostMsg => Unit): Unit =
    ascent.dom.window.addEventListener(
      "message",
      (event: ascent.dom.Event) =>
        event match
          case m: ascent.dom.MessageEvent =>
            val data = m.data
            // VS Code posts its own window messages (strings, style, etc). Treating
            // those as HostMsg decode errors re-renders, which posts more, until SIGKILL.
            if !VsCodeBridge.isHostPayload(data) then ()
            else
              val raw = js.JSON.stringify(data)
              Wire.hostMsgs(raw) match
                case Right(msgs) => msgs.foreach(f)
                case Left(err)   =>
                  Browser.console.error(err, raw)
                  post(WebviewMsg.Log(err))
                  f(HostMsg.Error(err, Some(Wire.Decode)))
          case _ => (),
    )
end VsCodeBridge

object VsCodeBridge:
  def isHostPayload(data: js.Any): Boolean =
    import groksbeard.facade.hasField
    data.hasField("_tag")
