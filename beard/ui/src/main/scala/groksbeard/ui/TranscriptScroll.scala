package groksbeard.ui

import ascent.Dom
import ascent.dom
import ascent.domtypes.Events
import groksbeard.core.TranscriptFollow
import zio.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.scalajs.js

object TranscriptScroll:
  def bind(el: dom.Element): URIO[Scope, Unit] =
    val follow       = new AtomicBoolean(true)
    def mark(): Unit =
      val on = TranscriptFollow.atTail(el.scrollTop, el.scrollHeight.toDouble, el.clientHeight.toDouble)
      follow.set(on)
      el.setAttribute("data-follow", if on then "true" else "false")
    def pin(): Unit =
      if follow.get() then el.scrollTop = el.scrollHeight.toDouble
    val obs = js.Dynamic.newInstance(js.Dynamic.global.MutationObserver) { (_: js.Any) =>
      val _ = js.Dynamic.global.window.requestAnimationFrame { (_: Double) =>
        pin()
      }
    }
    obs.observe(el, js.Dynamic.literal(childList = true, subtree = true, characterData = true))
    mark()
    pin()
    Dom.listen(el, Events.onScroll)(_ => ZIO.succeed(mark())) *>
      ZIO.addFinalizer(ZIO.succeed { obs.disconnect(); () }).unit
  end bind
end TranscriptScroll
