package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg}
import zio.json.*

import scala.scalajs.js

/** Browser host: POST WebviewMsg, SSE HostMsg. Served same-origin by [[groksbeard.preview.LiveMain]]. */
final class LivePreviewBridge extends HostBridge:
  def post(msg: WebviewMsg): Unit =
    val headers = js.Dictionary("content-type" -> "application/json")
    val _       = js.Dynamic.global.fetch(
      "/__beard/msg",
      js.Dynamic.literal(method = "POST", body = msg.toJson, headers = headers),
    )

  def onHost(f: HostMsg => Unit): Unit =
    val es = js.Dynamic.newInstance(js.Dynamic.global.EventSource)("/__beard/events")
    es.onmessage = (event: js.Dynamic) =>
      val data = "" + event.data
      data.fromJson[HostMsg].foreach(f)
end LivePreviewBridge
