package groksbeard.core

object Thought:
  val HeadlineMax: Int = 72

  def headline(full: String): String =
    val line = full.split("\\r?\\n").find(_.trim.nonEmpty).map(_.trim).getOrElse("")
    if line.length <= HeadlineMax then line else s"${line.take(HeadlineMax)}..."

  def summaryLabel(full: String, done: Boolean): String =
    val title = if done then "Thought" else "Thinking"
    val head  = headline(full)
    if head.isEmpty then title else s"$title: $head"
end Thought
