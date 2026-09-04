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
  def formatAtRef(chip: PromptChip): String =
    (chip.startLine, chip.endLine) match
      case (Some(start), Some(end)) => s"@${chip.path}:$start-$end"
      case _                        => s"@${chip.path}"
