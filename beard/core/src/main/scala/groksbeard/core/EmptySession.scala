package groksbeard.core

final class EmptySessionTracker:
  private val created = scala.collection.mutable.Set.empty[String]
  private val history = scala.collection.mutable.Set.empty[String]

  def markCreated(id: String): Unit = created += id

  def markHasHistory(id: String): Unit = history += id

  def shouldDelete(id: String): Boolean =
    created.contains(id) && !history.contains(id)

  def forget(id: String): Unit =
    created -= id
    history -= id
end EmptySessionTracker
