package groksbeard.core

enum ChangeKind:
  case Add, Modify, Delete, Move

object ChangeKind:
  def wire(kind: ChangeKind): String =
    kind match
      case ChangeKind.Add    => "add"
      case ChangeKind.Modify => "modify"
      case ChangeKind.Delete => "delete"
      case ChangeKind.Move   => "move"

  def fromWire(value: String): ChangeKind =
    value match
      case "add"    => ChangeKind.Add
      case "delete" => ChangeKind.Delete
      case "move"   => ChangeKind.Move
      case _        => ChangeKind.Modify
end ChangeKind

final case class FileChange(
    path: String,
    kind: ChangeKind,
    additions: Int,
    deletions: Int,
    wholeFile: Boolean,
    toolCallId: String,
    fromPath: Option[String] = None,
    oldSnapshot: Option[String] = None,
    newSnapshot: Option[String] = None,
    undoDisabled: Option[String] = None,
)

final case class ChangeSet(
    sessionId: String,
    turnId: String,
    title: String,
    files: List[FileChange],
    createdAt: Long,
)

enum UndoPlan:
  case Replace(path: String, text: String)
  case Create(path: String, text: String)
  case Delete(path: String, confirmIfDirty: Boolean)
  case MoveReverse(fromPath: String, toPath: String, oldText: String)
  case Disabled(reason: String)

enum UndoMutation:
  case Replace(path: String, text: String)
  case Create(path: String, text: String)
  case Delete(path: String)

enum UndoResolution:
  case Apply(mutations: List[UndoMutation])
  case Disabled(reason: String)
  case Cancelled

object ChangeSet:
  val SnapshotFileCap: Int        = 32
  val SnapshotByteCap: Int        = 16 * 1024 * 1024
  val MissingSnapshot: String     = "missing snapshot"
  val RegionOnly: String          = "region only"
  val MoveTargetUnknown: String   = "move target unknown"
  val PathExistsDifferent: String = "path exists with different content"

  def keepFile(set: ChangeSet, path: String): ChangeSet =
    set.copy(files = set.files.filterNot(_.path == path))

  def keepAll(set: ChangeSet): ChangeSet =
    set.copy(files = Nil)

  def lineStats(files: List[FileChange]): (Int, Int) =
    files.foldLeft((0, 0)) { case ((add, del), f) => (add + f.additions, del + f.deletions) }

  def formatStats(additions: Int, deletions: Int): String =
    s"+$additions/-$deletions"

  def turnTitle(text: String): String =
    text.split("\r?\n").iterator.map(_.trim).find(_.nonEmpty) match
      case None                            => "Untitled"
      case Some(line) if line.length <= 80 => line
      case Some(line)                      => s"${line.take(77)}..."

  def canStoreSnapshot(storedFiles: Int, storedBytes: Int, extraBytes: Int): Boolean =
    storedFiles < SnapshotFileCap && storedBytes + extraBytes <= SnapshotByteCap

  def snapshotBytes(change: FileChange): Int =
    change.oldSnapshot.map(Utf8.byteLength).getOrElse(0) +
      change.newSnapshot.map(Utf8.byteLength).getOrElse(0)

  def undoDisabledFor(kind: ChangeKind, wholeFile: Boolean, snapshotStored: Boolean): Option[String] =
    if !snapshotStored then Some(MissingSnapshot)
    else if kind == ChangeKind.Modify && !wholeFile then Some(RegionOnly)
    else None

  def shouldKeepExisting(
      existingWholeFile: Boolean,
      existingStored: Boolean,
      incomingWholeFile: Boolean,
      regionStandIn: Boolean,
  ): Boolean =
    existingStored && existingWholeFile && (!incomingWholeFile || regionStandIn)

  def lineDiffStats(oldText: String, newText: String): (Int, Int) =
    val oldLines = splitLines(oldText)
    val newLines = splitLines(newText)
    val lcs      = longestCommonSubsequence(oldLines, newLines)
    (newLines.length - lcs, oldLines.length - lcs)

  def undoPlan(change: FileChange, diskNow: Option[String] = None): UndoPlan =
    change.kind match
      case ChangeKind.Modify =>
        if !change.wholeFile then UndoPlan.Disabled(RegionOnly)
        else
          change.oldSnapshot match
            case None    => UndoPlan.Disabled(MissingSnapshot)
            case Some(s) => UndoPlan.Replace(change.path, s)
      case ChangeKind.Delete =>
        change.oldSnapshot match
          case None    => UndoPlan.Disabled(MissingSnapshot)
          case Some(s) =>
            diskNow match
              case Some(now) if now != s => UndoPlan.Disabled(PathExistsDifferent)
              case _                     => UndoPlan.Create(change.path, s)
      case ChangeKind.Add =>
        val dirty = diskNow.exists(now => change.newSnapshot.exists(_ != now))
        UndoPlan.Delete(change.path, confirmIfDirty = dirty)
      case ChangeKind.Move =>
        (change.fromPath, change.oldSnapshot) match
          case (None, _)             => UndoPlan.Disabled(MoveTargetUnknown)
          case (_, None)             => UndoPlan.Disabled(MissingSnapshot)
          case (Some(from), Some(s)) =>
            UndoPlan.MoveReverse(from, change.path, s)

  def mutationsFrom(plan: UndoPlan): List[UndoMutation] =
    plan match
      case UndoPlan.Replace(path, text)            => List(UndoMutation.Replace(path, text))
      case UndoPlan.Create(path, text)             => List(UndoMutation.Create(path, text))
      case UndoPlan.Delete(path, _)                => List(UndoMutation.Delete(path))
      case UndoPlan.MoveReverse(from, to, oldText) =>
        List(UndoMutation.Create(from, oldText), UndoMutation.Delete(to))
      case UndoPlan.Disabled(_) => Nil

  def resolveUndo(change: FileChange, diskNow: Option[String], confirmDirty: Boolean): UndoResolution =
    undoPlan(change, diskNow) match
      case UndoPlan.Disabled(reason)                                     => UndoResolution.Disabled(reason)
      case plan: UndoPlan.Delete if plan.confirmIfDirty && !confirmDirty =>
        UndoResolution.Cancelled
      case plan => UndoResolution.Apply(mutationsFrom(plan))

  def applyUndoToSnapshots(change: FileChange): Option[String] =
    undoPlan(change) match
      case UndoPlan.Delete(_, _) => None
      case _                     => change.oldSnapshot

  def toView(change: FileChange): ChangeFileView =
    ChangeFileView(
      path = change.path,
      kind = ChangeKind.wire(change.kind),
      additions = change.additions,
      deletions = change.deletions,
      wholeFile = change.wholeFile,
      undoDisabled = change.undoDisabled,
    )

  def summaryOf(files: List[FileChange]): ChangesSummary =
    val (add, del) = lineStats(files)
    ChangesSummary(files.size, add, del, files.map(toView))

  private def splitLines(text: String): Array[String] =
    text.split("\r?\n", -1)

  private def longestCommonSubsequence(a: Array[String], b: Array[String]): Int =
    val n = a.length
    val m = b.length
    if n == 0 || m == 0 then 0
    else
      var prev = Array.fill(m + 1)(0)
      var curr = Array.fill(m + 1)(0)
      var i    = 1
      while i <= n do
        var j = 1
        while j <= m do
          curr(j) =
            if a(i - 1) == b(j - 1) then prev(j - 1) + 1
            else math.max(prev(j), curr(j - 1))
          j += 1
        val tmp = prev
        prev = curr
        curr = tmp
        java.util.Arrays.fill(curr, 0)
        i += 1
      end while
      prev(m)
    end if
  end longestCommonSubsequence
end ChangeSet

final class ChangeStore:
  private var sets: List[ChangeSet] = Nil

  def list: List[ChangeSet] = sets

  def pending: List[FileChange] = sets.flatMap(_.files)

  def summary: ChangesSummary = ChangeSet.summaryOf(pending)

  def get(path: String): Option[FileChange] =
    pending.find(_.path == path)

  def ingest(sessionId: String, turnId: String, title: String, incoming: List[FileChange]): Unit =
    if incoming.isEmpty then ()
    else
      val idx = sets.indexWhere(s => s.sessionId == sessionId && s.turnId == turnId)
      val now = System.currentTimeMillis()
      if idx < 0 then sets = sets :+ ChangeSet(sessionId, turnId, title, incoming.map(withBudget), now)
      else
        val current = sets(idx)
        val merged  = incoming.foldLeft(current.files)(mergeFile)
        sets = sets.updated(idx, current.copy(title = title, files = merged))

  def keep(path: String): Unit =
    sets = sets.map(ChangeSet.keepFile(_, path)).filter(_.files.nonEmpty)

  def keepAll(): Unit =
    sets = Nil

  def drop(path: String): Unit = keep(path)

  private def mergeFile(files: List[FileChange], incoming: FileChange): List[FileChange] =
    val idx = files.indexWhere(_.path == incoming.path)
    if idx < 0 then files :+ withBudget(incoming)
    else
      val existing = files(idx)
      if ChangeSet.shouldKeepExisting(
          existing.wholeFile,
          existing.oldSnapshot.isDefined || existing.newSnapshot.isDefined,
          incoming.wholeFile,
          regionStandIn = incoming.wholeFile && incoming.undoDisabled.contains(ChangeSet.RegionOnly),
        )
      then files
      else files.updated(idx, withBudget(incoming))
    end if
  end mergeFile

  private def withBudget(change: FileChange): FileChange =
    val stored = sets.flatMap(_.files)
    val files  = stored.count(f => f.oldSnapshot.isDefined || f.newSnapshot.isDefined)
    val bytes  = stored.map(ChangeSet.snapshotBytes).sum
    val extra  = ChangeSet.snapshotBytes(change)
    if ChangeSet.canStoreSnapshot(files, bytes, extra) then
      change.copy(undoDisabled = ChangeSet.undoDisabledFor(change.kind, change.wholeFile, snapshotStored = true))
    else
      change.copy(
        oldSnapshot = None,
        newSnapshot = None,
        undoDisabled = Some(ChangeSet.MissingSnapshot),
      )
  end withBudget
end ChangeStore
