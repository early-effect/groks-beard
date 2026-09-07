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

  /** Drop a prefix so the remainder fits `cap` bytes, on a character boundary. */
  def keepTailToByteCap(text: String, cap: Int): String =
    val total = byteLength(text)
    if total <= cap then text
    else
      var skip = total - cap
      var i    = 0
      while i < text.length && skip > 0 do
        val c    = text.charAt(i).toInt
        val size =
          if c <= 0x7f then 1
          else if c <= 0x7ff then 2
          else if c >= 0xd800 && c <= 0xdbff then 4
          else 3
        skip -= size
        i += (if size == 4 then 2 else 1)
      end while
      text.substring(i)
    end if
  end keepTailToByteCap
end Utf8
