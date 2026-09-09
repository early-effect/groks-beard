package groksbeard.facade

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSGlobalScope}

@js.native
trait Console extends js.Object:
  def error(message: js.Any, more: js.Any*): Unit = js.native

class MicConstraints extends js.Object:
  val audio: Boolean = true
  val video: Boolean = false

@js.native
trait BeardClipboard extends js.Object:
  def writeText(data: String): js.Promise[Unit] = js.native
  def readText(): js.Promise[String]            = js.native

@js.native
trait BeardMediaDevices extends js.Object:
  def getUserMedia(constraints: MicConstraints): js.Promise[js.Object] = js.native

@js.native
trait BeardNavigator extends js.Object:
  def clipboard: BeardClipboard       = js.native
  def mediaDevices: BeardMediaDevices = js.native

@js.native
@JSGlobalScope
object Browser extends js.Object:
  val console: Console                            = js.native
  val navigator: BeardNavigator                   = js.native
  def parseInt(value: js.Any, radix: Int): Double = js.native

@js.native
trait JsError extends js.Object:
  def stack: js.UndefOr[String] = js.native

@js.native
@JSGlobal
class TextEncoder() extends js.Object:
  def encode(input: String): js.typedarray.Uint8Array = js.native

@js.native
@JSGlobal
class TextDecoder(@unused label: String) extends js.Object:
  def decode(input: js.typedarray.Uint8Array): String = js.native
