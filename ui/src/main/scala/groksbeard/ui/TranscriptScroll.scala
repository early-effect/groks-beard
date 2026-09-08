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
      try
        val _ = ascent.dom.window.requestAnimationFrame(run)
      catch case _: Throwable => run(0)
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
    val obs = new ascent.dom.MutationObserver((_, _) => raf(_ => pin()))
    obs.observe(el, JsDom.subtreeMutations)
    pin()
    mark()
    Dom.listen(el, Events.onScroll)(_ => ZIO.succeed(mark())) *>
      ZIO.addFinalizer(ZIO.succeed { obs.disconnect(); () }).unit
  end bind
end TranscriptScroll
