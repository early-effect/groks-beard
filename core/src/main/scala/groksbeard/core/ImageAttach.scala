package groksbeard.core

import zio.json.*
import ascent.squawk.Eq

final case class ImageChip(
    id: String,
    mime: String,
    data: String,
    name: String = "",
) derives JsonCodec,
      Eq

object ImageAttach:
  def dataUrl(chip: ImageChip): String =
    s"data:${chip.mime};base64,${chip.data}"

  def label(chip: ImageChip, index: Int): String =
    val n = if chip.name.nonEmpty then chip.name else s"Image #${index + 1}"
    n

  def mint(seq: Int, mime: String, data: String, name: String = ""): ImageChip =
    ImageChip(s"image-$seq", mime, data, name)

  def fromAcp(mime: String, data: String, uri: String = ""): ImageChip =
    val last = uri.replace('\\', '/').split('/').lastOption.getOrElse("")
    val name =
      if uri.startsWith("beard://image/") then uri.substring("beard://image/".length)
      else if last.nonEmpty then last
      else ""
    val id = if name.nonEmpty then name else s"image-${Integer.toUnsignedString(data.hashCode)}"
    ImageChip(id, if mime.nonEmpty then mime else "image/png", data, if name.nonEmpty then name else id)

  def isCaption(text: String): Boolean =
    val t = text.trim
    t.matches("(?i)\\[Image #\\d+\\]") || t.matches("(?i)\\[[^\\[\\]]+\\.(png|jpe?g|gif|webp)\\]")

  def blocks(chips: List[ImageChip], image: Boolean, embedded: Boolean): List[PromptBlock] =
    chips.zipWithIndex.flatMap { (chip, i) =>
      val caption = s"[${label(chip, i)}]"
      if image then List(PromptBlock.Image(data = chip.data, mimeType = chip.mime))
      else if embedded then
        List(
          PromptBlock.Text(caption),
          PromptBlock.Resource(
            EmbeddedResource(
              uri = s"beard://image/${chip.id}",
              mimeType = Some(chip.mime),
              blob = Some(chip.data),
            )
          ),
        )
      else List(PromptBlock.Text(caption))
      end if
    }
end ImageAttach
