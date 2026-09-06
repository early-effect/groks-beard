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

  def truncateToByteCap(text: String, cap: Int): String =
    if byteLength(text) <= cap then text
    else
      var n = 0
      var i = 0
      while i < text.length do
        val c    = text.charAt(i).toInt
        val size =
          if c <= 0x7f then 1
          else if c <= 0x7ff then 2
          else if c >= 0xd800 && c <= 0xdbff then 4
          else 3
        if n + size > cap then return text.substring(0, i)
        n += size
        i += (if size == 4 then 2 else 1)
      end while
      text.substring(0, i)
end Utf8
