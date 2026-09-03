package groksbeard.core

final case class PromptChip(
    path: String,
    absPath: String,
    source: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None,
    languageId: Option[String] = None,
    excerpt: Option[String] = None,
)

object PromptChip:
  def formatAtRef(chip: PromptChip): String =
    (chip.startLine, chip.endLine) match
      case (Some(start), Some(end)) => s"@${chip.path}:$start-$end"
      case _                        => s"@${chip.path}"
