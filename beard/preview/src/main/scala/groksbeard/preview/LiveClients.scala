package groksbeard.preview

import groksbeard.core.*
import zio.*
import zio.stream.*

final class LiveClients(
    slots: Ref[Map[String, LiveClients.Slot]],
    open: UIO[LiveSession],
    log: String => Unit,
):
  def post(id: String, msg: WebviewMsg): UIO[Unit] =
    getOrCreate(id).flatMap(_.session.post(msg))

  def eventStream(id: String): ZStream[Any, Nothing, HostMsg] =
    ZStream.unwrapScoped {
      subscribe(id).map(c => ZStream.fromHub(c.session.events))
    }

  def closeAll: UIO[Unit] =
    slots.get.flatMap { m =>
      ZIO.foreachDiscard(m.values) {
        case LiveClients.Slot.Open(c)    => c.session.close
        case LiveClients.Slot.Opening(p) =>
          p.await.timeout(2.seconds).flatMap {
            case Some(c) => c.session.close
            case None    => ZIO.unit
          }
      }
    }

  private def subscribe(id: String): ZIO[Scope, Nothing, LiveClients.Client] =
    getOrCreate(id).flatMap { c =>
      c.watchers.update(_ + 1) *>
        ZIO.addFinalizer(unsubscribe(id, c)) *>
        ZIO.succeed(c)
    }

  private def unsubscribe(id: String, c: LiveClients.Client): UIO[Unit] =
    c.watchers.updateAndGet(_ - 1).flatMap { n =>
      if n <= 0 then evict(id, c) else ZIO.unit
    }

  private def evict(id: String, c: LiveClients.Client): UIO[Unit] =
    slots
      .modify { m =>
        m.get(id) match
          case Some(LiveClients.Slot.Open(cur)) if cur eq c => (true, m - id)
          case _                                            => (false, m)
      }
      .flatMap { gone =>
        if gone then
          log(s"client $id: close")
          c.session.close
        else ZIO.unit
      }

  private def getOrCreate(id: String): UIO[LiveClients.Client] =
    Promise.make[Nothing, LiveClients.Client].flatMap { p =>
      slots
        .modify { m =>
          m.get(id) match
            case Some(LiveClients.Slot.Open(c))    => (Left(c), m)
            case Some(LiveClients.Slot.Opening(q)) => (Right(q), m)
            case None                              => (Right(p), m.updated(id, LiveClients.Slot.Opening(p)))
        }
        .flatMap {
          case Left(c)            => ZIO.succeed(c)
          case Right(q) if q eq p =>
            open
              .flatMap { session =>
                Ref.make(0).map(w => LiveClients.Client(session, w))
              }
              .onInterrupt(slots.update(_ - id))
              .flatMap { c =>
                log(s"client $id: open")
                slots.update(_.updated(id, LiveClients.Slot.Open(c))) *> p.succeed(c).as(c)
              }
          case Right(q) => q.await
        }
    }
end LiveClients

object LiveClients:
  val MaxId: Int = 128
  private val Id = raw"[A-Za-z0-9._-]{1,$MaxId}".r

  def validId(id: String): Boolean =
    id.nonEmpty && Id.matches(id)

  def make(open: UIO[LiveSession], log: String => Unit = _ => ()): UIO[LiveClients] =
    Ref.make(Map.empty[String, Slot]).map(LiveClients(_, open, log))

  def grok(log: String => Unit): ZIO[Scope, Nothing, LiveClients] =
    make(LiveSession.managed(log), log).tap(c => ZIO.addFinalizer(c.closeAll))

  def fake(): UIO[LiveClients] =
    make(LiveSession.fake(), _ => ())

  final class Client(val session: LiveSession, val watchers: Ref[Int])

  enum Slot:
    case Opening(p: Promise[Nothing, Client])
    case Open(c: Client)
end LiveClients
