package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class EmptyObject() derives JsonCodec

extension [A](value: A)(using enc: JsonEncoder[A]) def asJson: Json = enc.toJsonAST(value).getOrElse(Json.Null)

extension (json: Json) def as[A](using dec: JsonDecoder[A]): Either[String, A] = dec.fromJsonAST(json)
