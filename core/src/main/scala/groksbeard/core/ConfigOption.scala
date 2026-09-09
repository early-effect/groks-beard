package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class ConfigSelect(
    value: String,
    name: Option[String] = None,
    description: Option[String] = None,
) derives JsonCodec

final case class ConfigOption(
    id: String,
    name: String = "",
    category: Option[String] = None,
    @jsonField("type") tpe: String = "select",
    currentValue: Option[String] = None,
    options: List[ConfigSelect] = Nil,
) derives JsonCodec

object ConfigOption:
  val ModelKey: String  = "model"
  val EffortKey: String = "reasoning_effort"

  def current(opts: List[ConfigOption], id: String): Option[String] =
    opts.find(_.id == id).flatMap(_.currentValue).filter(_.nonEmpty)

  def of(json: Json): List[ConfigOption] =
    json match
      case obj: Json.Obj =>
        field(obj, "configOptions") match
          case Some(Json.Arr(items)) => items.toList.flatMap(one)
          case _                     => Nil
      case Json.Arr(items) => items.toList.flatMap(one)
      case _               => Nil

  def setParams(sessionId: SessionId, configId: String, value: String): Json =
    Json.Obj(
      "sessionId" -> Json.Str(sessionId.value),
      "configId"  -> Json.Str(configId),
      "value"     -> Json.Obj("value" -> Json.Str(value)),
    )

  def modelId(opts: List[ConfigOption]): Option[ModelId] =
    current(opts, ModelKey).map(groksbeard.core.ModelId(_))

  def effort(opts: List[ConfigOption]): Option[String] =
    current(opts, EffortKey)

  def efforts(opts: List[ConfigOption]): List[EffortLevel] =
    opts.find(_.id == EffortKey).toList.flatMap { opt =>
      if opt.options.nonEmpty then
        opt.options.map { sel =>
          EffortLevel(
            value = sel.value,
            label = sel.name,
            description = sel.description,
            default = opt.currentValue.filter(_ == sel.value).map(_ => true),
          )
        }
      else Nil
    }

  private def one(json: Json): Option[ConfigOption] =
    json match
      case obj: Json.Obj =>
        str(obj, "id").orElse(str(obj, "configId")).filter(_.nonEmpty).map { id =>
          ConfigOption(
            id = id,
            name = str(obj, "name").getOrElse(""),
            category = str(obj, "category"),
            tpe = str(obj, "type").getOrElse("select"),
            currentValue = str(obj, "currentValue").orElse(nestedValue(obj)),
            options = selects(obj),
          )
        }
      case _ => None

  private def selects(obj: Json.Obj): List[ConfigSelect] =
    field(obj, "options") match
      case Some(Json.Arr(items)) =>
        items.toList.flatMap {
          case Json.Str(v)     => Some(ConfigSelect(v))
          case inner: Json.Obj =>
            str(inner, "value").map { v =>
              ConfigSelect(v, str(inner, "name"), str(inner, "description"))
            }
          case _ => None
        }
      case _ => Nil

  private def nestedValue(obj: Json.Obj): Option[String] =
    field(obj, "value") match
      case Some(Json.Str(s))     => Some(s)
      case Some(inner: Json.Obj) =>
        str(inner, "value")
      case _ => None

  private def str(obj: Json.Obj, key: String): Option[String] =
    field(obj, key).collect { case Json.Str(s) if s.nonEmpty => s }

  private def field(obj: Json.Obj, key: String): Option[Json] =
    obj.fields.collectFirst { case (k, v) if k == key => v }
end ConfigOption
