package groksbeard.core

import zio.json.*

final case class EmbeddedResource(
    uri: String,
    mimeType: Option[String] = None,
    text: Option[String] = None,
    blob: Option[String] = None,
) derives JsonCodec

@jsonDiscriminator("type")
enum PromptBlock derives JsonCodec:
  @jsonHint("text") case Text(text: String)
  @jsonHint("resource") case Resource(resource: EmbeddedResource)
  @jsonHint("image") case Image(data: String, mimeType: String)

object PromptBlock:
  def text(body: String): PromptBlock = PromptBlock.Text(body)

  def fileResource(absPath: String, excerpt: String, languageId: Option[String]): PromptBlock =
    PromptBlock.Resource(
      EmbeddedResource(
        uri = fileUri(absPath),
        mimeType = mimeOf(languageId),
        text = Some(Utf8.truncateToByteCap(excerpt, PromptChip.EmbedByteCap)),
      )
    )

  def of(text: String, chips: List[PromptChip], embedded: Boolean): List[PromptBlock] =
    val blocks =
      if embedded then chips.flatMap(chipBlocks)
      else
        val body = PromptChip.buildPromptText("", chips).trim
        if body.isEmpty then Nil else List(PromptBlock.Text(body))
    val prompt = text.trim
    if prompt.isEmpty then blocks
    else blocks :+ PromptBlock.Text(prompt)

  def asText(blocks: List[PromptBlock]): String =
    blocks
      .collect {
        case PromptBlock.Text(t)                            => t
        case PromptBlock.Resource(res) if res.text.nonEmpty =>
          s"${res.uri}\n\n${res.text.get}"
        case PromptBlock.Resource(res) => res.uri
      }
      .filter(_.nonEmpty)
      .mkString("\n\n")

  private def chipBlocks(chip: PromptChip): List[PromptBlock] =
    val ref = PromptChip.formatAtRef(chip)
    chip.excerpt.filter(_.nonEmpty) match
      case None =>
        if ref.isEmpty then Nil else List(PromptBlock.Text(ref))
      case Some(excerpt) =>
        List(
          PromptBlock.Text(ref),
          fileResource(chip.absPath, excerpt, chip.languageId),
        )
  end chipBlocks

  private def fileUri(absPath: String): String =
    val norm = absPath.replace('\\', '/')
    if norm.startsWith("file:") then norm
    else if norm.startsWith("/") then s"file://$norm"
    else s"file:///$norm"

  private def mimeOf(languageId: Option[String]): Option[String] =
    languageId.filter(_.nonEmpty).map {
      case "scala" | "sc"      => "text/x-scala"
      case "java"              => "text/x-java-source"
      case "md" | "markdown"   => "text/markdown"
      case "json"              => "application/json"
      case "ts" | "typescript" => "text/typescript"
      case "js" | "javascript" => "text/javascript"
      case "py" | "python"     => "text/x-python"
      case other               => s"text/x-$other"
    }
end PromptBlock
