package groksbeard.core

object VoiceCapture:
  val NoDevice: String  = "voice.no-input-device"
  val Listening: String = "Listening…"
  val Off: String       = "Voice off"

  def append(draft: String, spoken: String): String =
    val t = spoken.trim
    if t.isEmpty then draft
    else if draft.isEmpty then t
    else if draft.endsWith(" ") || draft.endsWith("\n") then draft + t
    else s"$draft $t"
end VoiceCapture
