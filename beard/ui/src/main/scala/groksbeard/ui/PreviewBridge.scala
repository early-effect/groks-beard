package groksbeard.ui

import groksbeard.core.{HostBridge, HostMsg, WebviewMsg}

final class PreviewBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()

  def post(msg: WebviewMsg): Unit =
    msg match
      case WebviewMsg.Ready =>
        listener(HostMsg.Ready)
        listener(HostMsg.SessionMeta("preview", "Grok's Beard", "normal"))
      case WebviewMsg.Send(_) =>
        ()

  def onHost(f: HostMsg => Unit): Unit =
    listener = f
end PreviewBridge

object PreviewBridge:
  def sceneFromLocation: String =
    val search = ascent.dom.window.location.search
    val key    = "scene="
    val idx    = search.indexOf(key)
    if idx < 0 then "empty"
    else
      val rest = search.substring(idx + key.length)
      val amp  = rest.indexOf('&')
      val raw  = if amp < 0 then rest else rest.substring(0, amp)
      if raw.isEmpty then "empty" else raw
  end sceneFromLocation
end PreviewBridge
