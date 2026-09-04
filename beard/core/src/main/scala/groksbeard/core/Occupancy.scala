package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class Occupancy(used: Int, size: Int) derives JsonCodec

object Occupancy:
  def percent(used: Int, size: Int): Int =
    if size <= 0 then 0
    else math.max(0, math.min(100, math.round(used.toDouble / size * 100).toInt))

  def compact(value: Int): String =
    if value >= 1_000_000 then
      val n = value / 1_000_000.0
      if n == n.floor then s"${n.toInt}M" else f"$n%.1fM"
    else if value >= 1000 then
      val n = value / 1000.0
      if n == n.floor then s"${n.toInt}k" else f"$n%.1fk"
    else value.toString

  def label(used: Int, size: Int): String =
    s"${compact(used)} / ${compact(size)} · ${percent(used, size)}%"

  def tone(used: Int, size: Int): String =
    val p = percent(used, size)
    if p >= 85 then "hot" else if p >= 70 then "warn" else "ok"

  def fromJson(json: Json): Option[Occupancy] =
    json match
      case obj: Json.Obj =>
        def nest(key: String): Option[Occupancy] =
          obj.fields.collectFirst { case (k, inner) if k == key => fromJson(inner) }.flatten
        nest("usage").orElse(nest("_meta")).orElse(nest("update")).orElse {
          val used = intish(obj, "used")
            .orElse(intish(obj, "usedTokens"))
            .orElse(intish(obj, "tokens_used"))
            .orElse(intish(obj, "context_used"))
            .orElse(intish(obj, "inputTokens"))
            .orElse(intish(obj, "totalTokens"))
          val size = intish(obj, "size")
            .orElse(intish(obj, "maxTokens"))
            .orElse(intish(obj, "context_window"))
            .orElse(intish(obj, "contextWindow"))
          (used, size) match
            case (Some(u), Some(s)) if s > 0 => Some(Occupancy(u, s))
            case _                           => None
        }
      case _ => None

  private def intish(obj: Json.Obj, key: String): Option[Int] =
    obj.fields.collectFirst {
      case (k, Json.Num(n)) if k == key => n.intValue
    }
end Occupancy
