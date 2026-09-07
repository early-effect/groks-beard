package groksbeard.core

/** ACP `locations` on a tool call: which file the agent is in, and the optional 1-based line. */
final case class FollowTarget(path: String, line: Option[Int], toolCallId: ToolCallId)

object FollowAlong:
  val OriginalScheme: String = "beard-original"
  val ProposedScheme: String = "beard-proposed"

  /** vscode.ViewColumn.Beside. Open here when a Beard diff already holds the active editor. */
  val BesideColumn: Int = -2

  def pick(locations: List[ToolLocation], toolCallId: ToolCallId): Option[FollowTarget] =
    locations.reverseIterator.map(normalize).find(_.path.nonEmpty).map { loc =>
      FollowTarget(loc.path, loc.line, toolCallId)
    }

  def changed(prev: Option[FollowTarget], next: FollowTarget): Boolean =
    !prev.contains(next)

  def holdsDiff(scheme: String): Boolean =
    scheme == OriginalScheme || scheme == ProposedScheme

  def viewColumn(activeScheme: Option[String]): Option[Int] =
    if activeScheme.exists(holdsDiff) then Some(BesideColumn) else None

  private def normalize(loc: ToolLocation): ToolLocation =
    ToolLocation(loc.path.trim, loc.line.filter(_ > 0))
end FollowAlong
