package groksbeard.core

import zio.json.*
import zio.json.ast.Json

enum RenameOp:
  case Manual(title: String)
  case Auto

object SessionEdit:
  val AutoArg: String = "--auto"

  def parseRename(args: String): Either[String, RenameOp] =
    val t = args.trim
    if t.isEmpty then Left("empty")
    else if t == AutoArg then Right(RenameOp.Auto)
    else if t.startsWith(s"$AutoArg ") || t.startsWith(s"$AutoArg\t") then
      Left("/rename --auto must be the only argument")
    else Right(RenameOp.Manual(t))

  def findDir(fs: SessionFs, home: String, cwd: String, id: String): Option[String] =
    SessionIndex.groupDirs(fs, home, cwd).map(g => SessionIndex.join(g, id)).find(fs.isDirectory)

  def summaryPath(dir: String): String =
    SessionIndex.join(dir, "summary.json")

  def upsert(obj: Json.Obj, updates: List[(String, Json)]): Json.Obj =
    val keys = updates.map(_._1).toSet
    Json.Obj((obj.fields.toList.filterNot((k, _) => keys.contains(k)) ++ updates)*)

  def patchManual(raw: String, title: String): Option[String] =
    raw.fromJson[Json].toOption.collect { case obj: Json.Obj =>
      upsert(obj, List("session_summary" -> Json.Str(title), "title_is_manual" -> Json.Bool(true))).toJson
    }

  def patchAuto(raw: String): Option[String] =
    raw.fromJson[Json].toOption.collect { case obj: Json.Obj =>
      upsert(obj, List("title_is_manual" -> Json.Bool(false))).toJson
    }

  def seedManual(id: String, cwd: String, title: String): String =
    SessionSummary(
      SessionInfo(id, cwd),
      session_summary = Some(title),
      title_is_manual = Some(true),
    ).toJson

  def rename(fs: SessionFs, home: String, cwd: String, id: String, op: RenameOp): Option[SessionRow] =
    findDir(fs, home, cwd, id).flatMap { dir =>
      val path = summaryPath(dir)
      val next = fs.readText(path) match
        case Some(raw) =>
          op match
            case RenameOp.Manual(title) => patchManual(raw, title)
            case RenameOp.Auto          => patchAuto(raw)
        case None =>
          op match
            case RenameOp.Manual(title) => Some(seedManual(id, cwd, title))
            case RenameOp.Auto          => None
      next.foreach(fs.writeText(path, _))
      next.flatMap { _ =>
        SessionIndex.listRows(fs, home, cwd, Int.MaxValue).find(_.id == id).orElse {
          op match
            case RenameOp.Manual(title) => Some(SessionRow(id, title))
            case RenameOp.Auto          => None
        }
      }
    }

  def delete(fs: SessionFs, home: String, cwd: String, id: String): Boolean =
    findDir(fs, home, cwd, id) match
      case None      => false
      case Some(dir) =>
        fs.deleteTree(dir)
        true
end SessionEdit
