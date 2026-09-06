package groksbeard.core

final case class DiffSite(
    oldText: String,
    newText: String,
    oldLine: Option[Int] = None,
    newLine: Option[Int] = None,
)

final case class DiffExpandInput(
    diskText: Option[String],
    oldRegion: String,
    newRegion: String,
    diskIsBefore: Boolean,
    replaceAll: Boolean = false,
    sites: List[DiffSite] = Nil,
)

final case class DiffSides(
    oldText: String,
    newText: String,
    firstChangedLine: Int,
    wholeFile: Boolean,
)

object DiffExpand:
  val MaxBytes: Int = 2 * 1024 * 1024

  def expand(input: DiffExpandInput): DiffSides =
    val DiffExpandInput(diskText, oldRegion, newRegion, diskIsBefore, replaceAll, sites) = input
    if oversize(diskText) || oversize(Some(oldRegion)) || oversize(Some(newRegion)) then
      regionOnly(oldRegion, newRegion)
    else
      diskText match
        case None =>
          if oldRegion.isEmpty then wholeFile("", newRegion) else regionOnly(oldRegion, newRegion)
        case Some(disk) =>
          if oldRegion.isEmpty && diskIsBefore && disk.nonEmpty then wholeFile(disk, newRegion)
          else
            val needle      = if diskIsBefore then oldRegion else newRegion
            val replacement = if diskIsBefore then newRegion else oldRegion
            if needle.isEmpty then if diskIsBefore then wholeFile(disk, newRegion) else regionOnly(oldRegion, newRegion)
            else
              locateHaystack(disk, needle) match
                case None          => regionOnly(oldRegion, newRegion)
                case Some(located) =>
                  val siteLine = sites.headOption.flatMap(_.oldLine)
                  if replaceAll then
                    val nextText = replaceEvery(located.text, needle, replacement)
                    sidesFrom(located, nextText, diskIsBefore)
                  else
                    val at = pickIndex(located.text, needle, siteLine)
                    if at < 0 then regionOnly(oldRegion, newRegion)
                    else
                      val nextText = replaceOnceAt(located.text, at, needle, replacement)
                      sidesFrom(located, nextText, diskIsBefore)
            end if
    end if
  end expand

  private def oversize(text: Option[String]): Boolean =
    text.exists(t => Utf8.byteLength(t) > MaxBytes)

  private def firstChangedLine(oldText: String, newText: String): Int =
    val oldLines = oldText.split("\n", -1)
    val newLines = newText.split("\n", -1)
    val n        = math.min(oldLines.length, newLines.length)
    var i        = 0
    while i < n do
      if oldLines(i) != newLines(i) then return i
      i += 1
    if n == 0 then 0 else n

  private def regionOnly(oldRegion: String, newRegion: String): DiffSides =
    DiffSides(oldRegion, newRegion, firstChangedLine(oldRegion, newRegion), wholeFile = false)

  private def wholeFile(oldText: String, newText: String): DiffSides =
    DiffSides(oldText, newText, firstChangedLine(oldText, newText), wholeFile = true)

  private def sidesFrom(located: Located, nextText: String, diskIsBefore: Boolean): DiffSides =
    val proposed = emit(located.copy(text = nextText))
    val original = emit(located)
    if diskIsBefore then wholeFile(original, proposed) else wholeFile(proposed, original)

  private def indexesOf(haystack: String, needle: String): List[Int] =
    if needle.isEmpty then Nil
    else
      val out  = List.newBuilder[Int]
      var from = 0
      while from <= haystack.length do
        val at = haystack.indexOf(needle, from)
        if at < 0 then from = haystack.length + 1
        else
          out += at
          from = at + math.max(needle.length, 1)
      out.result()

  private def lineAtOffset(text: String, offset: Int): Int =
    var line  = 1
    val limit = math.min(offset, text.length)
    var i     = 0
    while i < limit do
      if text.charAt(i) == '\n' then line += 1
      i += 1
    line

  private def pickIndex(haystack: String, needle: String, siteLine: Option[Int]): Int =
    val hits = indexesOf(haystack, needle)
    if hits.isEmpty then -1
    else
      siteLine match
        case None       => hits.head
        case Some(line) =>
          hits.find(at => lineAtOffset(haystack, at) == line).getOrElse(hits.head)

  private def replaceOnceAt(haystack: String, start: Int, needle: String, replacement: String): String =
    haystack.substring(0, start) + replacement + haystack.substring(start + needle.length)

  private def replaceEvery(haystack: String, needle: String, replacement: String): String =
    if needle.isEmpty then haystack else haystack.replace(needle, replacement)

  private final case class Located(text: String, crlf: Boolean)

  private def locateHaystack(haystack: String, needle: String): Option[Located] =
    if needle.isEmpty || haystack.contains(needle) then Some(Located(haystack, haystack.contains("\r\n")))
    else if haystack.contains("\r\n") && !needle.contains("\r\n") then
      val lf = haystack.replace("\r\n", "\n")
      if lf.contains(needle) then Some(Located(lf, crlf = true)) else None
    else None

  private def emit(located: Located): String =
    if located.crlf && !located.text.contains("\r\n") then located.text.replace("\n", "\r\n")
    else located.text
end DiffExpand
