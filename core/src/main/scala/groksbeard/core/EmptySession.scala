package groksbeard.core

final class EmptySessionTracker:
  private val created = scala.collection.mutable.Set.empty[SessionId]
  private val history = scala.collection.mutable.Set.empty[SessionId]

  def markCreated(id: SessionId): Unit = created += id

  def markHasHistory(id: SessionId): Unit = history += id

  def shouldDelete(id: SessionId): Boolean =
    created.contains(id) && !history.contains(id)

  def forget(id: SessionId): Unit =
    created -= id
    history -= id
end EmptySessionTracker
