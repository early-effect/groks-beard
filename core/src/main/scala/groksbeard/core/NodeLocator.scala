package groksbeard.core

final case class LocateNode(
    nodePath: Option[String],
    pathEnv: String,
    win: Boolean,
    exists: String => Boolean,
)

object NodeLocator:
  def binaryName(win: Boolean): String = if win then "node.exe" else "node"

  def isAbsolute(path: String, win: Boolean): Boolean =
    if path.isEmpty then false
    else if win then path.matches("^[a-zA-Z]:[\\\\/].*") || path.startsWith("\\\\")
    else path.startsWith("/")

  def join(dir: String, name: String): String =
    s"${dir.replaceAll("[\\\\/]+$", "")}/$name"

  def candidates(input: LocateNode): List[String] =
    val bin = binaryName(input.win)
    val out = List.newBuilder[String]
    input.nodePath.filter(p => isAbsolute(p, input.win)).foreach(out += _)
    val sep = if input.win then ";" else ":"
    input.pathEnv.split(sep).foreach { dir =>
      if dir.trim.nonEmpty then
        out += join(dir, bin)
        if input.win then out += join(dir, "node.cmd")
    }
    out.result()
  end candidates

  def resolveSpawnTarget(candidate: String, exists: String => Boolean): String =
    if candidate.matches("(?i).*\\.(cmd|bat)$") then
      val exe = candidate.replaceAll("(?i)\\.(cmd|bat)$", ".exe")
      if exists(exe) then exe else candidate
    else candidate

  def locate(input: LocateNode): Either[List[String], String] =
    val searched = candidates(input)
    searched.find(input.exists) match
      case None      => Left(searched)
      case Some(hit) => Right(resolveSpawnTarget(hit, input.exists))
end NodeLocator
