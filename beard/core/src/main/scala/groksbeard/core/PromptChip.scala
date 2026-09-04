package groksbeard.core

import zio.json.*

final case class PromptChip(
    path: String,
    absPath: String,
    source: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None,
    @jsonExclude languageId: Option[String] = None,
    @jsonExclude excerpt: Option[String] = None,
) derives JsonCodec

object PromptChip:
  val EmbedByteCap: Int = 32 * 1024

  def formatAtRef(chip: PromptChip): String =
    (chip.startLine, chip.endLine) match
      case (Some(start), Some(end)) => s"@${chip.path}:$start-$end"
      case _                        => s"@${chip.path}"

  def workspaceRelativePath(absPath: String, workspaceRoot: Option[String]): String =
    workspaceRoot.filter(_.nonEmpty) match
      case None =>
        absPath.split("[\\\\/]").lastOption.getOrElse(absPath)
      case Some(root) =>
        val prefix = root.replaceAll("[\\\\/]+$", "").replace('\\', '/') + "/"
        val norm   = absPath.replace('\\', '/')
        if norm.startsWith(prefix) then norm.substring(prefix.length)
        else absPath.split("[\\\\/]").lastOption.getOrElse(absPath)

  def fromSelection(
      absPath: String,
      workspaceRoot: Option[String] = None,
      startLine: Option[Int] = None,
      endLine: Option[Int] = None,
      languageId: Option[String] = None,
      excerpt: Option[String] = None,
  ): PromptChip =
    PromptChip(
      path = workspaceRelativePath(absPath, workspaceRoot),
      absPath = absPath,
      source = "selection",
      startLine = startLine,
      endLine = endLine,
      languageId = languageId.filter(_.nonEmpty),
      excerpt = excerpt.filter(_.nonEmpty),
    )

  def fromFile(
      absPath: String,
      workspaceRoot: Option[String] = None,
      languageId: Option[String] = None,
      source: String = "file",
  ): PromptChip =
    PromptChip(
      path = workspaceRelativePath(absPath, workspaceRoot),
      absPath = absPath,
      source = source,
      languageId = languageId.filter(_.nonEmpty),
    )

  def formatChipBlock(chip: PromptChip): String =
    val ref = formatAtRef(chip)
    chip.excerpt.filter(_.nonEmpty) match
      case None      => ref
      case Some(raw) =>
        val body  = Utf8.truncateToByteCap(raw, EmbedByteCap)
        val fence = fenceTicks(body)
        val lang  = fenceLang(chip)
        s"$ref\n\n$fence$lang\n$body\n$fence"

  def buildPromptText(text: String, chips: List[PromptChip]): String =
    (chips.map(formatChipBlock) :+ text).filter(_.nonEmpty).mkString("\n\n")

  def chipsForSend(
      chips: List[PromptChip],
      activeFile: Option[PromptChip],
      includeActiveFileByDefault: Boolean,
  ): List[PromptChip] =
    if chips.nonEmpty then chips
    else if includeActiveFileByDefault then activeFile.toList
    else Nil

  def sameRange(a: PromptChip, b: PromptChip): Boolean =
    a.absPath == b.absPath && a.startLine == b.startLine

  def upsert(chips: List[PromptChip], chip: PromptChip): List[PromptChip] =
    chips.filterNot(sameRange(_, chip)) :+ chip

  def key(chip: PromptChip): String =
    s"${chip.absPath}:${chip.startLine.getOrElse(0)}:${chip.endLine.getOrElse(0)}"

  private def fenceLang(chip: PromptChip): String =
    chip.languageId.filter(_.nonEmpty) match
      case Some("md") => "markdown"
      case Some(id)   => id
      case None       => ""

  private def fenceTicks(text: String): String =
    var ticks = "```"
    while text.contains(ticks) do ticks = ticks + "`"
    ticks
end PromptChip
