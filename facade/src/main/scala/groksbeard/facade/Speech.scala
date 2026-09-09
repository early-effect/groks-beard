package groksbeard.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
trait SpeechRecognitionAlternative extends js.Object:
  def transcript: String = js.native
  def confidence: Double = js.native

@js.native
trait SpeechRecognitionResult extends js.Object:
  def length: Int                                    = js.native
  def isFinal: Boolean                               = js.native
  def item(index: Int): SpeechRecognitionAlternative = js.native

@js.native
trait SpeechRecognitionResultList extends js.Object:
  def length: Int                               = js.native
  def item(index: Int): SpeechRecognitionResult = js.native

@js.native
trait SpeechRecognitionEvent extends js.Object:
  def resultIndex: Int                     = js.native
  def results: SpeechRecognitionResultList = js.native

@js.native
trait SpeechRecognitionErrorEvent extends js.Object:
  def error: String   = js.native
  def message: String = js.native

@js.native
trait SpeechRecInstance extends js.Object:
  var continuous: Boolean                                      = js.native
  var interimResults: Boolean                                  = js.native
  var lang: String                                             = js.native
  var maxAlternatives: Int                                     = js.native
  var onresult: js.Function1[SpeechRecognitionEvent, Unit]     = js.native
  var onerror: js.Function1[SpeechRecognitionErrorEvent, Unit] = js.native
  var onend: js.Function0[Unit]                                = js.native
  def start(): Unit                                            = js.native
  def stop(): Unit                                             = js.native
  def abort(): Unit                                            = js.native
end SpeechRecInstance

@js.native
@JSGlobal("SpeechRecognition")
class SpeechRecognition() extends js.Object with SpeechRecInstance

@js.native
@JSGlobal("webkitSpeechRecognition")
class WebkitSpeechRecognition() extends js.Object with SpeechRecInstance

object SpeechRec:
  def create(): Option[SpeechRecInstance] =
    try Some(new SpeechRecognition())
    catch
      case _: Throwable =>
        try Some(new WebkitSpeechRecognition())
        catch case _: Throwable => None

  def finalTranscript(e: SpeechRecognitionEvent): String =
    if e == null || e.results == null then ""
    else
      (0 until e.results.length).toList
        .flatMap { i =>
          val row = e.results.item(i)
          if row == null || !row.isFinal || row.length == 0 then None
          else Option(row.item(0)).map(_.transcript).map(_.trim).filter(_.nonEmpty)
        }
        .mkString(" ")
end SpeechRec
