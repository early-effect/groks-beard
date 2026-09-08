package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg, Wire}
import groksbeard.facade.Browser
import zio.json.*

import scala.scalajs.js

/** Browser host: POST WebviewMsg, SSE HostMsg. Served same-origin by [[groksbeard.preview.LiveMain]].
  *
  * Each page load mints a client id so two tabs (or Firefox vs Playwright) do not share a ChatRuntime.
  *
  * POSTs wait until EventSource is open so ChatApp's Ready cannot beat Hub.subscribe on the sidecar.
  */
final class LivePreviewBridge extends HostBridge:
  private val client                   = LivePreviewBridge.newClientId()
  private var opened: Boolean          = false
  private var queued: List[WebviewMsg] = Nil

  def post(msg: WebviewMsg): Unit =
    if opened then send(msg)
    else queued = queued :+ msg

  def onHost(f: HostMsg => Unit): Unit =
    val es = new ascent.dom.EventSource(LivePreviewBridge.eventsPath(client))
    es.onopen = (_: js.Any) =>
      opened = true
      val msgs = queued
      queued = Nil
      msgs.foreach(send)
    es.onmessage = (event: js.Any) =>
      val data = "" + event.asInstanceOf[ascent.dom.MessageEvent].data
      Wire.hostMsgs(data) match
        case Right(msgs) => msgs.foreach(f)
        case Left(err)   =>
          Browser.console.error(err, data)
          f(HostMsg.Error(err, Some(Wire.Decode)))
  end onHost

  private def send(msg: WebviewMsg): Unit =
    val _ = ascent.dom.window.fetch(
      LivePreviewBridge.msgPath(client),
      JsDom.postJson(msg.toJson),
    )
end LivePreviewBridge

object LivePreviewBridge:
  def eventsPath(client: String): String = s"/__beard/events?client=$client"
  def msgPath(client: String): String    = s"/__beard/msg?client=$client"

  def newClientId(): String = ascent.dom.window.crypto.randomUUID()
