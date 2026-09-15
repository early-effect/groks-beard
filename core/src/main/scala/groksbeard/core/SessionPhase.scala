package groksbeard.core

enum Replay:
  case Follow, Ignore

enum SessionPhase:
  case Idle
  case Empty
  case ResumeDisk(id: SessionId)
  case ResumeEmpty(id: SessionId)
  case ResumePainted(id: SessionId)
  case Attaching(id: SessionId, replay: Replay, painted: Boolean)
  case Live(id: SessionId)

object SessionPhase:
  extension (p: SessionPhase)
    def pendingResume: Option[SessionId] =
      p match
        case ResumeDisk(id)         => Some(id)
        case ResumeEmpty(id)        => Some(id)
        case ResumePainted(id)      => Some(id)
        case Attaching(id, _, _)    => Some(id)
        case Idle | Empty | Live(_) => None

    def loading: Boolean =
      p match
        case ResumeDisk(_) | ResumeEmpty(_)            => true
        case Attaching(_, _, painted)                  => !painted
        case Idle | Empty | ResumePainted(_) | Live(_) => false

    def metaLoading: Boolean = p.pendingResume.nonEmpty || p.loading

    def diskPainted: Boolean =
      p match
        case ResumePainted(_) | Attaching(_, _, true) => true
        case _                                        => false

    def paintDone: Boolean =
      p match
        case ResumeEmpty(_) | ResumePainted(_) | Attaching(_, _, _) => true
        case _                                                      => false

    def attaching: Boolean =
      p match
        case Attaching(_, _, _) => true
        case _                  => false

    def ignoreReplay: Boolean =
      p match
        case Attaching(_, Replay.Ignore, _) => true
        case _                              => false

    def loadCleared: Boolean = p.pendingResume.nonEmpty
  end extension

  def beginNew: SessionPhase = Empty

  def beginResume(id: SessionId): SessionPhase = ResumeDisk(id)

  def cancel(p: SessionPhase): (SessionPhase, Option[SessionId]) =
    p.pendingResume match
      case None     => (p, None)
      case Some(id) => (Empty, Some(id))

  def onDisk(p: SessionPhase, hasTurns: Boolean): SessionPhase =
    p match
      case ResumeDisk(id) => if hasTurns then ResumePainted(id) else ResumeEmpty(id)
      case other          => other

  def startAttach(p: SessionPhase, useLoad: Boolean): SessionPhase =
    p.pendingResume match
      case None     => p
      case Some(id) =>
        val painted = p.diskPainted
        val replay  = if painted && useLoad then Replay.Ignore else Replay.Follow
        Attaching(id, replay, painted)

  def attached(p: SessionPhase, sessionId: Option[SessionId]): SessionPhase =
    p.pendingResume.orElse(sessionId).filter(_.nonEmpty) match
      case Some(id) => Live(id)
      case None     => Empty

  def fail(p: SessionPhase): SessionPhase =
    p match
      case Idle | Empty => p
      case _            => Empty
end SessionPhase
