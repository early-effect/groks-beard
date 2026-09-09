package groksbeard.core

object ChildAttach:
  def isChild(sessionId: SessionId, parent: Option[SessionId], childIds: Set[SessionId]): Boolean =
    sessionId.nonEmpty && parent.exists(_ != sessionId) && childIds.contains(sessionId)

  def idsOf(rows: List[TaskRow]): Set[SessionId] =
    rows.iterator.filter(_.kind == TaskKind.Subagent).map(r => SessionId(r.id.value)).filter(_.nonEmpty).toSet

  def fold(
      turns: List[TurnView],
      msg: HostMsg,
  ): List[TurnView] =
    ChatModel.applyMsg(ChatModel.empty.copy(turns = turns), msg).turns

  def activity(turns: List[TurnView]): String =
    val last = turns.lastOption
    last.flatMap(_.stopReason) match
      case None =>
        last
          .flatMap(_.tools.reverse.find(t => ToolStatus.isLive(t.status)).map(_.title))
          .orElse(last.filter(_.thought.nonEmpty).map(_ => "Thinking"))
          .orElse(Some("Running"))
          .get
      case Some(StopReason.Cancelled) => "Cancelled"
      case Some(StopReason.EndTurn)   => "Completed"
      case Some(other)                => StopReason.wire(other)
    end match
  end activity
end ChildAttach
