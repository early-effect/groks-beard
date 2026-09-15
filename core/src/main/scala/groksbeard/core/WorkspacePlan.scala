package groksbeard.core

object WorkspacePlan:
  val Rel: String     = ".grok/plan.md"
  val Missing: String = "No plan written yet"

  def path(cwd: String): String =
    SessionIndex.workspacePlanPath(cwd)

  def isSessionPlan(path: String): Boolean =
    val posix = path.replace('\\', '/')
    posix.contains("/.grok/sessions/") && posix.endsWith("/plan.md")

  def rewrite(path: String, cwd: String): String =
    if isSessionPlan(path) then SessionIndex.workspacePlanPath(cwd) else path

  /** Keep `%2F` in session paths: `Uri.parse` would otherwise treat it as `/`. */
  def encodeUriPath(path: String): String =
    path.replace("%", "%25")
end WorkspacePlan
