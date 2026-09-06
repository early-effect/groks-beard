package groksbeard.ui

import ascent.Location

/** Same-document URLs for preview history. ACP still maps these to host messages. */
object BeardPath:
  val Welcome: String = "/"

  def sessionHref(id: String): String = s"?session=$id"

  def sessionId(loc: Location): Option[String] =
    loc.param("session").filter(_.nonEmpty)

  def sceneName(loc: Location): Option[String] =
    loc.param("scene").filter(_.nonEmpty)
end BeardPath
