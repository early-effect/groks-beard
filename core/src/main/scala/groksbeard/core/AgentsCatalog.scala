package groksbeard.core

import zio.json.*

final case class AgentDef(
    name: String,
    description: String = "",
    path: String = "",
    builtin: Boolean = false,
) derives JsonCodec

final case class PersonaDef(
    name: String,
    description: String = "",
    path: String = "",
    editable: Boolean = true,
) derives JsonCodec

object AgentsCatalog:
  val EmptyNotice: String = "No agent definitions found"

  def parseAgent(path: String, text: String): Option[AgentDef] =
    val name = fileStem(path)
    if name.isEmpty then None
    else
      val fm   = frontmatter(text)
      val desc = fm.get("description").orElse(firstParagraph(stripFrontmatter(text))).getOrElse("")
      Some(
        AgentDef(
          name = fm.get("name").getOrElse(name),
          description = desc,
          path = path,
          builtin = false,
        )
      )
    end if
  end parseAgent

  def parsePersona(path: String, text: String): Option[PersonaDef] =
    val name = fileStem(path)
    if name.isEmpty then None
    else
      val desc =
        tomlString(text, "description")
          .orElse(tomlString(text, "instructions").map(s => s.linesIterator.find(_.trim.nonEmpty).getOrElse(s).trim))
          .getOrElse("")
      Some(PersonaDef(name = name, description = desc, path = path, editable = !path.contains("/bundled/")))

  def builtins: List[AgentDef] =
    List(
      AgentDef("general-purpose", "Default type. Full-capability agent for any task.", builtin = true),
      AgentDef("explore", "Research agent. Searches, reads, greps, and runs shell commands.", builtin = true),
      AgentDef("plan", "Planning agent. Explores the codebase and produces a plan.", builtin = true),
    )

  def merge(disk: List[AgentDef]): List[AgentDef] =
    val names = disk.map(_.name.toLowerCase).toSet
    disk ++ builtins.filterNot(b => names.contains(b.name.toLowerCase))

  private def fileStem(path: String): String =
    val base = path.split("[\\\\/]").lastOption.getOrElse(path)
    val i    = base.lastIndexOf('.')
    if i <= 0 then base else base.take(i)

  private def frontmatter(text: String): Map[String, String] =
    val t = text.trim
    if !t.startsWith("---") then Map.empty
    else
      val rest = t.drop(3).dropWhile(_ == '\n')
      val end  = rest.indexOf("\n---")
      val body = if end < 0 then rest else rest.take(end)
      body.linesIterator.flatMap { line =>
        val i = line.indexOf(':')
        if i <= 0 then None
        else Some(line.take(i).trim -> line.drop(i + 1).trim.replaceAll("^['\"]|['\"]$", ""))
      }.toMap
    end if
  end frontmatter

  private def stripFrontmatter(text: String): String =
    val t = text.trim
    if !t.startsWith("---") then t
    else
      val rest = t.drop(3)
      val end  = rest.indexOf("\n---")
      if end < 0 then t
      else rest.drop(end + 4).trim

  private def firstParagraph(text: String): Option[String] =
    text.linesIterator.map(_.trim).find(_.nonEmpty)

  private def tomlString(text: String, key: String): Option[String] =
    val prefix = s"$key"
    text.linesIterator
      .map(_.trim)
      .find(l => l.startsWith(s"$prefix =") || l.startsWith(s"$prefix="))
      .map { line =>
        val raw = line.drop(line.indexOf('=') + 1).trim
        raw.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
      }
      .filter(_.nonEmpty)
  end tomlString
end AgentsCatalog
