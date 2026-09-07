package groksbeard.core

import ascent.squawk.Eq
import zio.json.*
import zio.json.ast.Json

final case class EffortLevel(
    value: String,
    label: Option[String] = None,
    description: Option[String] = None,
    default: Option[Boolean] = None,
) derives JsonCodec,
      Eq

object Effort:
  val Tui: List[String] = List("low", "medium", "high", "xhigh")
  val NoModel: String   = "No active model to apply effort to"

  def grokMeta(active: String = "high"): Json =
    val levels = Tui.map { v =>
      if v == "high" then Json.Obj("value" -> Json.Str(v), "default" -> Json.Bool(true))
      else Json.Obj("value"                -> Json.Str(v))
    }
    Json.Obj(
      "supportsReasoningEffort" -> Json.Bool(true),
      "reasoningEffort"         -> Json.Str(active),
      "reasoningEfforts"        -> Json.Arr(levels*),
    )
  end grokMeta

  def of(model: Option[ModelOption]): List[EffortLevel] =
    val fromMeta = model.toList.flatMap(effortsOf)
    if fromMeta.nonEmpty then fromMeta
    else if supports(model) then Tui.map(v => EffortLevel(v))
    else Nil

  def supports(model: Option[ModelOption]): Boolean =
    model.exists { m =>
      effortsOf(m).nonEmpty || flag(m._meta, "supportsReasoningEffort").contains(true)
    }

  def defaultOf(model: Option[ModelOption]): String =
    of(model).find(_.default.contains(true)).map(_.value).getOrElse("")

  def activeOf(model: Option[ModelOption]): String =
    model.flatMap(activeEffort).filter(_.nonEmpty).getOrElse(defaultOf(model))

  def parse(args: String): Either[String, String] =
    val t = args.trim
    if t.isEmpty then Left("empty")
    else Right(canonicalize(t))

  def pick(query: String, allowed: List[EffortLevel]): Option[EffortLevel] =
    val q = canonicalize(query)
    if q.isEmpty || allowed.isEmpty then None
    else
      allowed
        .find(_.value.equalsIgnoreCase(q))
        .orElse(allowed.find(_.label.exists(_.equalsIgnoreCase(query.trim))))
        .orElse(allowed.find(_.value.toLowerCase.startsWith(q)))

  def names(allowed: List[EffortLevel]): String =
    allowed.map(_.value).mkString(", ")

  def chip(modelName: String, effort: String): String =
    if effort.isEmpty then modelName else s"$modelName · $effort"

  def unknown(query: String, allowed: List[EffortLevel]): String =
    if allowed.isEmpty then "Current model does not support reasoning effort"
    else s"Unknown effort level '$query'; use one of: ${names(allowed)}"

  def splitModelArgs(
      args: String,
      models: List[ModelOption],
  ): Either[String, (ModelOption, Option[String])] =
    val t = args.trim
    if t.isEmpty then Left("Unknown model: ")
    else
      ModelOption.pick(t, models) match
        case Some(m) => Right((m, None))
        case None    =>
          val tokens = t.split("\\s+").toList
          val peeled =
            List(1, 2).flatMap { n =>
              if tokens.size <= n then None
              else
                val effortQ = tokens.takeRight(n).mkString(" ")
                val modelQ  = tokens.dropRight(n).mkString(" ")
                ModelOption.pick(modelQ, models).map(_ -> effortQ)
            }.headOption
          peeled match
            case None               => Left(s"Unknown model: $args")
            case Some((m, effortQ)) =>
              val allowed = of(Some(m))
              pick(effortQ, allowed) match
                case Some(e) => Right((m, Some(e.value)))
                case None    => Left(unknown(effortQ, allowed))
    end if
  end splitModelArgs

  private def canonicalize(raw: String): String =
    val n = raw.trim.toLowerCase.replace('_', '-').replace('-', ' ').replaceAll("\\s+", " ")
    n match
      case "x high" | "extra high" | "extra" => "xhigh"
      case "min"                             => "minimal"
      case other                             => other.replace(" ", "")

  private def effortsOf(model: ModelOption): List[EffortLevel] =
    model._meta match
      case Some(obj: Json.Obj) =>
        field(obj, "reasoningEfforts").orElse(field(obj, "reasoning_efforts")) match
          case Some(Json.Arr(items)) => items.toList.flatMap(effortFrom)
          case _                     => Nil
      case _ => Nil

  private def effortFrom(json: Json): Option[EffortLevel] =
    json match
      case Json.Str(v)   => Some(EffortLevel(v))
      case obj: Json.Obj =>
        field(obj, "value").collect {
          case Json.Str(v) if v.nonEmpty =>
            EffortLevel(
              value = v,
              label = field(obj, "label").collect { case Json.Str(s) => s },
              description = field(obj, "description").collect { case Json.Str(s) => s },
              default = field(obj, "default").collect { case Json.Bool(b) => b },
            )
        }
      case _ => None

  private def activeEffort(model: ModelOption): Option[String] =
    model._meta.collect { case obj: Json.Obj =>
      field(obj, "reasoningEffort")
        .orElse(field(obj, "reasoning_effort"))
        .collect { case Json.Str(s) => s }
    }.flatten

  private def flag(meta: Option[Json], key: String): Option[Boolean] =
    meta.collect { case obj: Json.Obj =>
      field(obj, key).collect { case Json.Bool(b) => b }
    }.flatten

  private def field(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (k, v) if k == key => v }
end Effort
