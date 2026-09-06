package groksbeard.core

object Ndjson:
  def split(buffer: String, chunk: String): (List[String], String) =
    val combined = buffer + chunk
    val parts    = combined.split("\n", -1).toList
    val rest     = parts.lastOption.getOrElse("")
    val lines    = parts.dropRight(1).map(_.trim).filter(_.nonEmpty)
    (lines, rest)

  def encode(jsonLine: String): String =
    jsonLine + "\n"

  def encodeChunk(jsonLines: List[String]): String =
    if jsonLines.isEmpty then "" else jsonLines.mkString("\n") + "\n"
end Ndjson
