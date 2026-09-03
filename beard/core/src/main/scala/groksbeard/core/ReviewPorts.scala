package groksbeard.core

final case class DiffPair(path: String, oldText: String, newText: String, wholeFile: Boolean = true)

final case class ReviewPorts(
    readDisk: String => Option[String] = _ => None,
    openNativeDiffs: (String, List[DiffPair]) => Unit = (_, _) => (),
    applyUndo: List[UndoMutation] => Unit = _ => (),
    confirmDirty: String => Boolean = _ => true,
    onStoreChange: () => Unit = () => (),
)

object ReviewPorts:
  val ignore: ReviewPorts = ReviewPorts()
