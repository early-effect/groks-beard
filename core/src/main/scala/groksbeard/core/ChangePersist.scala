package groksbeard.core

import java.nio.charset.StandardCharsets

import zio.json.*

final case class ChangeIndexFile(
    path: String,
    kind: ChangeKind,
    additions: Int,
    deletions: Int,
    wholeFile: Boolean,
    toolCallId: ToolCallId,
    fromPath: Option[String] = None,
    undoDisabled: Option[String] = None,
    hasOld: Boolean = false,
    hasNew: Boolean = false,
) derives JsonCodec

final case class ChangeIndexSet(
    sessionId: SessionId,
    turnId: TurnId,
    title: String,
    files: List[ChangeIndexFile],
    createdAt: Long,
) derives JsonCodec

object ChangePersist:
  val IndexKey: String = "groksBeard.changes.index"

  def encodePath(path: String): String =
    val bytes = path.getBytes(StandardCharsets.UTF_8)
    val sb    = new StringBuilder(bytes.length * 3)
    var i     = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      val c = b.toChar
      if isUnreserved(c) then sb.append(c)
      else sb.append(percentEncode(b))
      i += 1
    sb.toString
  end encodePath

  def rel(sessionId: SessionId, turnId: TurnId, path: String, side: String): String =
    s"${sessionId.value}/${turnId.value}/${encodePath(path)}.$side"

  def rels(sets: List[ChangeSet]): List[String] =
    sets.flatMap { s =>
      s.files.flatMap { f =>
        List(
          f.oldSnapshot.map(_ => rel(s.sessionId, s.turnId, f.path, "old")),
          f.newSnapshot.map(_ => rel(s.sessionId, s.turnId, f.path, "new")),
        ).flatten
      }
    }

  def index(sets: List[ChangeSet]): List[ChangeIndexSet] =
    sets.map { s =>
      ChangeIndexSet(
        sessionId = s.sessionId,
        turnId = s.turnId,
        title = s.title,
        createdAt = s.createdAt,
        files = s.files.map { f =>
          ChangeIndexFile(
            path = f.path,
            kind = f.kind,
            additions = f.additions,
            deletions = f.deletions,
            wholeFile = f.wholeFile,
            toolCallId = f.toolCallId,
            fromPath = f.fromPath,
            undoDisabled = f.undoDisabled,
            hasOld = f.oldSnapshot.isDefined,
            hasNew = f.newSnapshot.isDefined,
          )
        },
      )
    }

  def hydrate(rows: List[ChangeIndexSet], read: String => Option[String]): List[ChangeSet] =
    rows.map { s =>
      val files = s.files.map { f =>
        val oldText = if f.hasOld then read(rel(s.sessionId, s.turnId, f.path, "old")) else None
        val newText = if f.hasNew then read(rel(s.sessionId, s.turnId, f.path, "new")) else None
        val missing = (f.hasOld && oldText.isEmpty) || (f.hasNew && newText.isEmpty)
        FileChange(
          path = f.path,
          kind = f.kind,
          additions = f.additions,
          deletions = f.deletions,
          wholeFile = f.wholeFile,
          toolCallId = f.toolCallId,
          fromPath = f.fromPath,
          oldSnapshot = oldText,
          newSnapshot = newText,
          undoDisabled = if missing then Some(ChangeSet.MissingSnapshot) else f.undoDisabled,
        )
      }
      ChangeSet(s.sessionId, s.turnId, s.title, files, s.createdAt)
    }

  def bodies(sets: List[ChangeSet]): List[(String, String)] =
    sets.flatMap { s =>
      s.files.flatMap { f =>
        List(
          f.oldSnapshot.map(text => rel(s.sessionId, s.turnId, f.path, "old") -> text),
          f.newSnapshot.map(text => rel(s.sessionId, s.turnId, f.path, "new") -> text),
        ).flatten
      }
    }

  private def isUnreserved(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.'

  private val Hex = "0123456789ABCDEF"

  private def percentEncode(b: Int): String =
    s"%${Hex.charAt((b >> 4) & 0xf)}${Hex.charAt(b & 0xf)}"
end ChangePersist
