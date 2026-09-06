package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg}
import zio.json.*

import scala.scalajs.js

/** Browser host: POST WebviewMsg, SSE HostMsg. Served same-origin by [[groksbeard.preview.LiveMain]].
  *
  * Each page load mints a client id so two tabs (or Firefox vs Playwright) do not share a ChatRuntime.
  */
final class LivePreviewBridge extends HostBridge:
  private val client = LivePreviewBridge.newClientId()

  def post(msg: WebviewMsg): Unit =
    val headers = js.Dictionary("content-type" -> "application/json")
    val _       = js.Dynamic.global.fetch(
      LivePreviewBridge.msgPath(client),
      js.Dynamic.literal(method = "POST", body = msg.toJson, headers = headers),
    )

  def onHost(f: HostMsg => Unit): Unit =
    val es = js.Dynamic.newInstance(js.Dynamic.global.EventSource)(LivePreviewBridge.eventsPath(client))
    es.onmessage = (event: js.Dynamic) =>
      val data = "" + event.data
      data.fromJson[HostMsg].foreach(f)
end LivePreviewBridge

object LivePreviewBridge:
  def eventsPath(client: String): String = s"/__beard/events?client=$client"
  def msgPath(client: String): String    = s"/__beard/msg?client=$client"

  def newClientId(): String =
    js.Dynamic.global.crypto.randomUUID().asInstanceOf[String]
end LivePreviewBridge
