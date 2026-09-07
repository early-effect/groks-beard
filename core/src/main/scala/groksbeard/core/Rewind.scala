package groksbeard.core

import ascent.squawk.Eq
import zio.json.*
import zio.json.ast.Json

final case class RewindPoint(
    promptIndex: Int,
    preview: String,
    fileSnapshots: Int = 0,
) derives JsonCodec,
      Eq

object Rewind:
  def fromTurns(turns: List[TurnView]): List[RewindPoint] =
    turns.iterator
      .flatMap(_.user)
      .filter(_.text.trim.nonEmpty)
      .zipWithIndex
      .map { (u, i) =>
        RewindPoint(i, Thought.headline(u.text.trim))
      }
      .toList

  def truncate(turns: List[TurnView], promptIndex: Int): List[TurnView] =
    var n = -1
    turns.filter { t =>
      if t.user.exists(_.text.trim.nonEmpty) then n += 1
      n <= promptIndex
    }

  def decodePoints(json: Json): List[RewindPoint] =
    json.as[RewindPointsResult].toOption.map(_.points).filter(_.nonEmpty).getOrElse(decodeList(json))

  def decodeList(json: Json): List[RewindPoint] =
    json match
      case obj: Json.Obj =>
        obj.fields
          .collectFirst {
            case (k, arr: Json.Arr) if k == "points" || k == "rewind_points" || k == "rewindPoints" =>
              arr.elements.toList.flatMap(decodeOne)
          }
          .getOrElse(Nil)
      case arr: Json.Arr => arr.elements.toList.flatMap(decodeOne)
      case _             => Nil

  def decodeOne(json: Json): Option[RewindPoint] =
    json match
      case obj: Json.Obj =>
        val idx     = intField(obj, "prompt_index").orElse(intField(obj, "promptIndex"))
        val preview =
          strField(obj, "prompt_preview")
            .orElse(strField(obj, "promptPreview"))
            .orElse(strField(obj, "prompt_text"))
            .orElse(strField(obj, "promptText"))
            .orElse(strField(obj, "preview"))
            .getOrElse("")
        val snaps =
          intField(obj, "num_file_snapshots")
            .orElse(intField(obj, "numFileSnapshots"))
            .orElse(intField(obj, "fileSnapshots"))
            .getOrElse(0)
        idx.map(i => RewindPoint(i, Thought.headline(preview.trim), snaps))
      case _ => None

  private def strField(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (k, Json.Str(s)) if k == key => s }

  private def intField(obj: Json.Obj, key: String): Option[Int] =
    obj.fields.collectFirst {
      case (k, Json.Num(n)) if k == key => n.intValue
    }
end Rewind

final case class RewindPointsParams(sessionId: SessionId) derives JsonCodec

final case class RewindPointsResult(points: List[RewindPoint] = Nil) derives JsonCodec

final case class RewindExecuteParams(
    sessionId: SessionId,
    @jsonField("prompt_index") promptIndex: Int,
) derives JsonCodec
