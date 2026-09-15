package groksbeard.ui

import ascent.Dom
import ascent.dom
import ascent.domtypes.Events
import groksbeard.core.TranscriptFollow
import groksbeard.core.TranscriptPageFlip
import zio.*

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.scalajs.js

object TranscriptScroll:
  private val goTail = new AtomicReference[Option[() => Unit]](None)
  private val goFlip = new AtomicReference[Option[() => Unit]](None)

  def jump(): Unit =
    goTail.get().foreach(_())

  def pageFlip(): Unit =
    goFlip.get().foreach(_())

  def bind(el: dom.Element): URIO[Scope, Unit] =
    val follow                   = new AtomicBoolean(true)
    val flipping                 = new AtomicBoolean(false)
    val ignore                   = new AtomicBoolean(false)
    var pinned                   = 0d
    def live(run: => Unit): Unit =
      try run
      catch case _: Throwable => ()
    def atTail: Boolean =
      TranscriptFollow.atTail(el.scrollTop, el.scrollHeight.toDouble, el.clientHeight.toDouble)
    def writeFollow(on: Boolean): Unit =
      follow.set(on)
      el.setAttribute("data-follow", if on then "true" else "false")
    def mark(): Unit =
      live {
        val on = atTail
        if ignore.get() && math.abs(el.scrollTop - pinned) <= TranscriptFollow.SlackPx then ()
        else writeFollow(on)
      }
    def padNode: Option[dom.Element] =
      Option(el.querySelector("""[data-testid="transcript-pad"]"""))
    def lastTurn: Option[dom.Element] =
      val nodes = el.querySelectorAll("""[data-testid^="turn-"]""")
      val n     = nodes.length
      if n == 0 then None
      else
        nodes.item(n - 1) match
          case e: dom.Element => Some(e)
          case _              => None
    def setPad(px: Double): Unit =
      padNode.foreach { p =>
        p.setAttribute("style", s"height:${px.max(0).toInt}px;flex-shrink:0;pointer-events:none")
      }
    def contentY(node: dom.Element): Double =
      node.getBoundingClientRect().top + el.scrollTop - el.getBoundingClientRect().top
    def layout(): Unit =
      live {
        if flipping.get() then
          lastTurn match
            case None =>
              setPad(0)
              el.scrollTop = el.scrollHeight.toDouble
            case Some(turn) =>
              setPad(0)
              val viewport = el.clientHeight.toDouble
              val turnTop  = contentY(turn)
              val turnH    = turn.getBoundingClientRect().height
              val content  = el.scrollHeight.toDouble
              val pad      = TranscriptPageFlip.padPx(viewport, turnTop, content)
              setPad(pad)
              el.scrollTop = TranscriptPageFlip.scrollTop(turnTop, turnH, viewport, content)
        else
          setPad(0)
          el.scrollTop = el.scrollHeight.toDouble
        end if
        pinned = el.scrollTop
      }
    var rafId                                      = Option.empty[Int]
    def raf(run: js.Function1[Double, Unit]): Unit =
      try rafId = Some(ascent.dom.window.requestAnimationFrame(run))
      catch case _: Throwable => run(0)
    def pin(): Unit =
      try
        if follow.get() then
          ignore.set(true)
          layout()
          ignore.set(false)
          raf { _ =>
            try
              if follow.get() then
                ignore.set(true)
                layout()
                ignore.set(false)
              mark()
            catch case _: Throwable => ()
          }
      catch case _: Throwable => ()
    def toTail(): Unit =
      live {
        flipping.set(false)
        follow.set(true)
        writeFollow(true)
        pin()
      }
    def toFlip(): Unit =
      live {
        flipping.set(true)
        follow.set(true)
        writeFollow(true)
        pin()
      }
    val obs = new ascent.dom.MutationObserver((_, _) => raf(_ => pin()))
    obs.observe(el, JsDom.subtreeMutations)
    goTail.set(Some(toTail))
    goFlip.set(Some(toFlip))
    pin()
    mark()
    Dom.listen(el, Events.onScroll)(_ => ZIO.succeed(mark())) *>
      ZIO
        .addFinalizer(ZIO.succeed {
          goTail.set(None)
          goFlip.set(None)
          obs.disconnect()
          rafId.foreach { id =>
            try ascent.dom.window.cancelAnimationFrame(id)
            catch case _: Throwable => ()
          }
          ()
        })
        .unit
  end bind
end TranscriptScroll
