package groksbeard.core

import zio.*

enum BeardError:
  case SystemError(cause: Throwable)
  case DecodeError(detail: String)
  case Missing(what: String)

  def message: String =
    this match
      case BeardError.SystemError(cause) =>
        Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.toString)
      case BeardError.DecodeError(detail) => detail
      case BeardError.Missing(what)       => s"$what not found"
end BeardError

object BeardError:
  type Result[+A] = IO[BeardError, A]

  def system(cause: Throwable): BeardError = SystemError(cause)

  def decode(detail: String): BeardError = DecodeError(detail)

  def missing(what: String): BeardError = Missing(what)

  extension [R, A](zio: ZIO[R, Throwable, A]) def orSystem: ZIO[R, BeardError, A] = zio.mapError(system)
end BeardError
