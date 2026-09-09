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
