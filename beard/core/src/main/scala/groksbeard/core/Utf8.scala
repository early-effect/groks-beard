package groksbeard.core

object Utf8:
  def byteLength(text: String): Int =
    var n = 0
    var i = 0
    while i < text.length do
      val c = text.charAt(i).toInt
      if c <= 0x7f then n += 1
      else if c <= 0x7ff then n += 2
      else if c >= 0xd800 && c <= 0xdbff then
        n += 4
        i += 1
      else n += 3
      i += 1
    n
  end byteLength
end Utf8
