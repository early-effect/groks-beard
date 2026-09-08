package groksbeard.ui

import ascent.ast.AscentEvent
import ascent.dom

import scala.scalajs.js

extension (el: dom.Element)
  def queryHtml(sel: String): dom.HTMLElement =
    el.querySelector(sel) match
      case h: dom.HTMLElement => h
      case _                  => throw new NoSuchElementException(sel)

extension (ev: AscentEvent)
  def keyboard: Option[dom.KeyboardEvent] =
    ev.raw match
      case k: dom.KeyboardEvent => Some(k)
      case _                    => None

object JsDom:
  def postJson(body: String): dom.RequestInit =
    new JsonPost("POST", body, js.Dictionary("content-type" -> "application/json"))
      .asInstanceOf[dom.RequestInit]

  def subtreeMutations: dom.MutationObserverInit =
    new SubtreeWatch(childList = true, subtree = true, characterData = true)
      .asInstanceOf[dom.MutationObserverInit]

  def keyDown(key: String, code: String, ctrl: Boolean = false): dom.KeyboardEvent =
    new dom.KeyboardEvent(
      "keydown",
      new KeyInit(key, code, ctrl, bubbles = true, cancelable = true).asInstanceOf[dom.KeyboardEventInit],
    )

  private class JsonPost(
      val method: String,
      val body: String,
      val headers: js.Dictionary[String],
  ) extends js.Object

  private class SubtreeWatch(
      val childList: Boolean,
      val subtree: Boolean,
      val characterData: Boolean,
  ) extends js.Object

  private class KeyInit(
      val key: String,
      val code: String,
      val ctrlKey: Boolean,
      val bubbles: Boolean,
      val cancelable: Boolean,
  ) extends js.Object
end JsDom
