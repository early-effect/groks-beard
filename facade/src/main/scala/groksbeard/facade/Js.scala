package groksbeard.facade

import scala.scalajs.js
import scala.scalajs.js.JavaScriptException

extension (value: js.Any)
  def asInt: Option[Int] =
    if js.typeOf(value) == "number" then Some(JsNative.int(value)) else None

  def asNonEmptyString: String = JsNative.nonEmptyString(value)

  def hasField(name: String): Boolean =
    value != null && !js.isUndefined(value) && js.typeOf(value) == "object" && JsNative.hasOwn(value, name)

extension (t: Throwable)
  def jsStack: Option[String] =
    t match
      case JavaScriptException(ex) => JsNative.stack(ex)
      case _                       => None

private object JsNative:
  def int(value: js.Any): Int = value.asInstanceOf[Int]

  def nonEmptyString(value: js.Any): String =
    if js.typeOf(value) != "string" then ""
    else
      val s = value.asInstanceOf[String]
      if s != null && s.nonEmpty then s else ""

  def hasOwn(value: js.Any, name: String): Boolean =
    js.Object.hasProperty(value.asInstanceOf[js.Object], name)

  def stack(ex: Any): Option[String] =
    if ex == null then None
    else ex.asInstanceOf[JsError].stack.toOption.filter(s => s != null && s.nonEmpty)
end JsNative
