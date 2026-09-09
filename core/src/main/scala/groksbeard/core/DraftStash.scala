package groksbeard.core

final case class DraftStash(
    text: String,
    chips: List[PromptChip] = Nil,
    images: List[ImageChip] = Nil,
    restoreAfterSend: Boolean = false,
)

object DraftStash:
  val Caption: String       = "Stashed"
  val RestoredToast: String = "Stashed draft restored."
  val SavedToast: String    = "Draft stashed. Press the stash key again to restore it."

  def isEmpty(text: String, chips: List[PromptChip], images: List[ImageChip] = Nil): Boolean =
    text.trim.isEmpty && chips.isEmpty && images.isEmpty

  def take(
      text: String,
      chips: List[PromptChip],
      restoreAfterSend: Boolean,
      images: List[ImageChip] = Nil,
  ): Option[DraftStash] =
    if isEmpty(text, chips, images) then None
    else Some(DraftStash(text, chips, images, restoreAfterSend))

  def historyHead(stash: Option[DraftStash], entries: List[String]): List[String] =
    stash.map(_.text.trim).filter(_.nonEmpty) match
      case Some(text) if !entries.headOption.contains(text) => text :: entries
      case _                                                => entries
end DraftStash
