package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class EmptyObject() derives JsonCodec

/** zio-json string codecs for opaque ids and closed enums. */
object JsonExt:
  def stringCodec[A](to: A => String, from: String => A): JsonCodec[A] =
    JsonCodec(JsonEncoder.string.contramap(to), JsonDecoder.string.map(from))

  def stringCodecOrFail[A](to: A => String, from: String => Either[String, A]): JsonCodec[A] =
    JsonCodec(JsonEncoder.string.contramap(to), JsonDecoder.string.mapOrFail(from))
end JsonExt

extension [A](value: A)(using enc: JsonEncoder[A]) def asJson: Json = enc.toJsonAST(value).getOrElse(Json.Null)

extension (json: Json) def as[A](using dec: JsonDecoder[A]): Either[String, A] = dec.fromJsonAST(json)
