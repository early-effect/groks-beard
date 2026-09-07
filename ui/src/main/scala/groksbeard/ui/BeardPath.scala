package groksbeard.ui

import ascent.Location
import groksbeard.core.SessionId

/** Same-document URLs for preview history. ACP still maps these to host messages. */
object BeardPath:
  val Welcome: String = "/"

  def sessionHref(id: SessionId): String = s"?session=${id.value}"

  def sessionId(loc: Location): Option[SessionId] =
    loc.param("session").filter(_.nonEmpty).map(SessionId(_))

  def sceneName(loc: Location): Option[String] =
    loc.param("scene").filter(_.nonEmpty)
end BeardPath
