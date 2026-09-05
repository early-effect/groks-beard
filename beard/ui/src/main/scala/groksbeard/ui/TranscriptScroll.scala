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
    val ignore       = new AtomicBoolean(false)
    def mark(): Unit =
      if ignore.get() then ()
      else
        val on = TranscriptFollow.atTail(el.scrollTop, el.scrollHeight.toDouble, el.clientHeight.toDouble)
        follow.set(on)
        el.setAttribute("data-follow", if on then "true" else "false")
    def stick(): Unit                              = el.scrollTop = el.scrollHeight.toDouble
    def raf(run: js.Function1[Double, Unit]): Unit =
      val w = js.Dynamic.global.window
      if js.typeOf(w.requestAnimationFrame) == "function" then
        val _ = w.requestAnimationFrame(run)
      else run(0)
    def pin(): Unit =
      if follow.get() then
        ignore.set(true)
        stick()
        raf { _ =>
          if follow.get() then stick()
          raf { _ =>
            if follow.get() then stick()
            ignore.set(false)
            mark()
          }
        }
    val obs = js.Dynamic.newInstance(js.Dynamic.global.MutationObserver) { (_: js.Any) =>
      raf { _ => pin() }
    }
    obs.observe(el, js.Dynamic.literal(childList = true, subtree = true, characterData = true))
    pin()
    Dom.listen(el, Events.onScroll)(_ => ZIO.succeed(mark())) *>
      ZIO.addFinalizer(ZIO.succeed { obs.disconnect(); () }).unit
  end bind
end TranscriptScroll
