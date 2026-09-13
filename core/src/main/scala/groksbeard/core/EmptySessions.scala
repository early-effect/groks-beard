package groksbeard.core

import zio.*

trait EmptySessions:
  def markCreated(id: SessionId): UIO[Unit]
  def markHasHistory(id: SessionId): UIO[Unit]
  def createdByUs(id: SessionId): UIO[Boolean]
  def shouldDelete(id: SessionId): UIO[Boolean]
  def forget(id: SessionId): UIO[Unit]

final case class EmptySessionsLive(ref: Ref[EmptySessions.State]) extends EmptySessions:
  def markCreated(id: SessionId): UIO[Unit] =
    ref.update(s => s.copy(created = s.created + id))

  def markHasHistory(id: SessionId): UIO[Unit] =
    ref.update(s => s.copy(history = s.history + id))

  def createdByUs(id: SessionId): UIO[Boolean] =
    ref.get.map(_.created.contains(id))

  def shouldDelete(id: SessionId): UIO[Boolean] =
    ref.get.map(s => s.created.contains(id) && !s.history.contains(id))

  def forget(id: SessionId): UIO[Unit] =
    ref.update(s => s.copy(created = s.created - id, history = s.history - id))

object EmptySessions:
  final case class State(
      created: Set[SessionId] = Set.empty,
      history: Set[SessionId] = Set.empty,
  )

  val layer: ULayer[EmptySessions] =
    ZLayer.fromZIO(Ref.make(State()).map(EmptySessionsLive(_)))
end EmptySessions
