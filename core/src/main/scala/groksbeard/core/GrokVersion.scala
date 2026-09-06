package groksbeard.core

final case class GrokVersion(
    major: Int,
    minor: Int,
    patch: Int,
    git: Option[String] = None,
    channel: Option[String] = None,
    raw: String,
)

object GrokVersion:
  private val Triple = raw"^(\d+)\.(\d+)\.(\d+)".r
  private val Git    = raw"^\(([0-9a-fA-F]+)\)".r
  private val Chan   = raw"^\[([^\]]+)\]".r

  def parse(stdout: String): Option[GrokVersion] =
    val line =
      stdout.split('\n').iterator.map(_.trim).find(_.nonEmpty).getOrElse("")
    val trimmed = line.stripSuffix("\r")
    if !trimmed.toLowerCase.startsWith("grok ") then None
    else
      val after = trimmed.substring(5).trim
      Triple.findPrefixMatchOf(after).map { m =>
        var rest = after.substring(m.end).trim
        val git  = Git.findPrefixMatchOf(rest).map { gm =>
          rest = rest.substring(gm.end).trim
          gm.group(1)
        }
        val channel = Chan.findPrefixMatchOf(rest).map(_.group(1))
        GrokVersion(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, git, channel, trimmed)
      }
    end if
  end parse

  def compare(a: GrokVersion, major: Int, minor: Int, patch: Int): Int =
    if a.major != major then a.major - major
    else if a.minor != minor then a.minor - minor
    else a.patch - patch

  def isAtLeast(version: GrokVersion, major: Int, minor: Int, patch: Int): Boolean =
    compare(version, major, minor, patch) >= 0
end GrokVersion
