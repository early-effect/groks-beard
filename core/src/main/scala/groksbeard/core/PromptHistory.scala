package groksbeard.core

/** This session's sent prompts, newest first. `↑` on an empty composer and `/history` both read this list. */
object PromptHistory:
  def entries(model: ChatModel): List[String] =
    val sent = model.turns.flatMap(_.user.map(_.text.trim).filter(_.nonEmpty))
    val q    = model.queue.map(_.text.trim).filter(_.nonEmpty)
    (sent ++ q).reverse

  def query(draft: String): Option[String] =
    val t = draft.trim
    if t == "/history" then Some("")
    else if t.startsWith("/history ") then Some(t.drop("/history ".length))
    else None

  def filter(entries: List[String], query: String): List[String] =
    val q = query.trim.toLowerCase
    if q.isEmpty then entries
    else
      val prefix = entries.filter(_.toLowerCase.startsWith(q))
      val mid    = entries.filter { t =>
        val low = t.toLowerCase
        !low.startsWith(q) && low.contains(q)
      }
      prefix ++ mid
  end filter

  def older(index: Int, size: Int): Int =
    if size <= 0 then 0 else math.min(size - 1, index + 1)

  def newer(index: Int): Option[Int] =
    if index <= 0 then None else Some(index - 1)

  def label(text: String, limit: Int = 72): String =
    val one = text.linesIterator.find(_.trim.nonEmpty).getOrElse(text).trim
    if one.length <= limit then one else s"${one.take(limit - 1)}…"
end PromptHistory
