package groksbeard.core

object MentionSearch:
  val FileLimit: Int      = 12
  val ExcludeGlob: String = "{**/node_modules/**,**/.git/**,**/target/**,**/dist/**}"

  def pattern(query: String): Option[String] =
    val q = query.trim.replaceAll("[*?{}\\[\\]\\\\]", "")
    if q.isEmpty then None else Some(s"**/*$q*")

  def basename(path: String): String =
    val posix = path.replace('\\', '/')
    posix.substring(posix.lastIndexOf('/') + 1)

  def rank(files: List[MentionFile], query: String): List[MentionFile] =
    val q = query.trim.toLowerCase
    files.sortBy { file =>
      val name   = basename(file.path).toLowerCase
      val bucket =
        if name.startsWith(q) then 0
        else if name.contains(q) then 1
        else if file.path.toLowerCase.contains(q) then 2
        else 3
      (bucket, file.path.length, file.path)
    }
  end rank
end MentionSearch
