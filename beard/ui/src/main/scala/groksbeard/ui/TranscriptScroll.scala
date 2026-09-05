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
    val follow          = new AtomicBoolean(true)
    val ignore          = new AtomicBoolean(false)
    var pinned          = 0d
    def atTail: Boolean =
      TranscriptFollow.atTail(el.scrollTop, el.scrollHeight.toDouble, el.clientHeight.toDouble)
    def writeFollow(on: Boolean): Unit =
      follow.set(on)
      el.setAttribute("data-follow", if on then "true" else "false")
    def mark(): Unit =
      val on = atTail
      if ignore.get() && math.abs(el.scrollTop - pinned) <= TranscriptFollow.SlackPx then ()
      else writeFollow(on)
    def stick(): Unit =
      el.scrollTop = el.scrollHeight.toDouble
      pinned = el.scrollTop
    def raf(run: js.Function1[Double, Unit]): Unit =
      val w = js.Dynamic.global.window
      if js.typeOf(w.requestAnimationFrame) == "function" then
        val _ = w.requestAnimationFrame(run)
      else run(0)
    def pin(): Unit =
      if follow.get() then
        ignore.set(true)
        stick()
        ignore.set(false)
        raf { _ =>
          if follow.get() then
            ignore.set(true)
            stick()
            ignore.set(false)
          mark()
        }
    val obs = js.Dynamic.newInstance(js.Dynamic.global.MutationObserver) { (_: js.Any) =>
      raf { _ => pin() }
    }
    obs.observe(el, js.Dynamic.literal(childList = true, subtree = true, characterData = true))
    pin()
    mark()
    Dom.listen(el, Events.onScroll)(_ => ZIO.succeed(mark())) *>
      ZIO.addFinalizer(ZIO.succeed { obs.disconnect(); () }).unit
  end bind
end TranscriptScroll
