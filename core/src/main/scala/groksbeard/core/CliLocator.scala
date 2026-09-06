package groksbeard.core

final case class LocateGrok(
    cliPath: Option[String],
    env: String => Option[String],
    win: Boolean,
    exists: String => Boolean,
)

object CliLocator:
  def binaryName(win: Boolean): String = if win then "grok.exe" else "grok"

  def join(dir: String, name: String): String =
    s"${dir.replaceAll("[\\\\/]+$", "")}/$name"

  def resolveSpawnTarget(candidate: String, exists: String => Boolean): String =
    val lower = candidate.toLowerCase
    if lower.endsWith(".cmd") || lower.endsWith(".bat") then
      val exe = candidate.substring(0, candidate.length - 4) + ".exe"
      if exists(exe) then exe else candidate
    else candidate

  def candidates(input: LocateGrok): List[String] =
    val bin = binaryName(input.win)
    val out = List.newBuilder[String]
    input.cliPath.filter(_.nonEmpty).foreach(out += _)
    out += join(join(GrokHome(input.env), "bin"), bin)
    val pathEnv = input.env("PATH").orElse(input.env("Path")).getOrElse("")
    val sep     = if input.win then ";" else ":"
    pathEnv.split(sep).foreach { dir =>
      if dir.trim.nonEmpty then
        out += join(dir, bin)
        if input.win then out += join(dir, "grok.cmd")
    }
    out.result()
  end candidates

  def locate(input: LocateGrok): Either[List[String], String] =
    val searched = candidates(input)
    searched.find(input.exists) match
      case None      => Left(searched)
      case Some(hit) => Right(resolveSpawnTarget(hit, input.exists))
end CliLocator
