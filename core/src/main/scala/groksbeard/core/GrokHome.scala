package groksbeard.core

object GrokHome:
  def apply(env: String => Option[String]): String =
    env("GROK_HOME").filter(_.nonEmpty) match
      case Some(home) => home
      case None       =>
        val home = env("HOME").orElse(env("USERPROFILE")).filter(_.nonEmpty)
        home match
          case None    => ".grok"
          case Some(h) => s"${h.replaceAll("[\\\\/]+$", "")}/.grok"
end GrokHome
