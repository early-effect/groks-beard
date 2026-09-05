package groksbeard.core

import zio.json.*

final case class SlashCommand(name: String, description: String, @jsonExclude hint: Option[String] = None)
    derives JsonCodec

final case class MentionFile(path: String, absPath: String) derives JsonCodec

final case class ModeOption(id: String, name: String) derives JsonCodec

final case class ModelOption(modelId: String, name: String, description: Option[String] = None) derives JsonCodec

object ModelOption:
  def label(id: String, models: List[ModelOption]): String =
    models.find(_.modelId == id).map(_.name).filter(_.nonEmpty).getOrElse {
      if id.nonEmpty then id else "Model"
    }

  def pick(query: String, models: List[ModelOption]): Option[ModelOption] =
    val q = query.trim.toLowerCase
    if q.isEmpty || models.isEmpty then None
    else
      models
        .find(_.modelId.toLowerCase == q)
        .orElse(models.find(_.name.toLowerCase == q))
        .orElse(models.find(_.name.toLowerCase.contains(q)))
        .orElse(models.find(_.modelId.toLowerCase.contains(q)))
end ModelOption

object ComposerQuery:

  def slashQuery(draft: String): Option[String] =
    if !draft.startsWith("/") then None
    else
      val first = draft.split("\\s", 2).headOption.getOrElse(draft)
      if first.contains('\n') then None
      else Some(first.drop(1))

  def filterSlash(commands: List[SlashCommand], query: String): List[SlashCommand] =
    val q = query.stripPrefix("/").toLowerCase
    if q.isEmpty then commands
    else
      val prefix = commands.filter(_.name.toLowerCase.startsWith(q))
      val mid    = commands.filter { c =>
        val name = c.name.toLowerCase
        !name.startsWith(q) && name.contains(q)
      }
      val desc = commands.filter { c =>
        val name = c.name.toLowerCase
        !name.startsWith(q) && !name.contains(q) && c.description.toLowerCase.contains(q)
      }
      prefix ++ mid ++ desc
    end if
  end filterSlash

  def mentionQuery(draft: String): Option[String] =
    val rx = "(?:^|\\s)@([^\\s]*)$".r
    rx.findFirstMatchIn(draft).map(_.group(1))

  def mentionPopoverOpen(draft: String, dismissed: Boolean): Boolean =
    !dismissed && mentionQuery(draft).isDefined

  def mentionChoices(
      draft: String,
      hostQuery: String,
      files: List[MentionFile],
      dismissed: Boolean,
  ): List[MentionFile] =
    mentionQuery(draft) match
      case Some(q) if mentionPopoverOpen(draft, dismissed) && q == hostQuery => files
      case _                                                                 => Nil

  def moveMentionIndex(current: Option[Int], key: String, count: Int): Option[Int] =
    if count <= 0 then None
    else
      val start = current.getOrElse(0)
      key match
        case "ArrowDown" => Some(math.min(count - 1, start + (if current.isEmpty then 0 else 1)))
        case "ArrowUp"   => Some(math.max(0, if current.isEmpty then 0 else start - 1))
        case _           => current

  def permissionOption(key: String, options: List[PermissionOption]): Option[PermissionOption] =
    digitIndex(key).flatMap(options.lift)

  def questionOption(key: String, questions: List[AgentQuestion]): Option[(String, String)] =
    questions.headOption.flatMap { q =>
      val idx =
        digitIndex(key).orElse {
          if key.length == 1 && key(0) >= 'a' && key(0) <= 'f' then Some(9 + (key(0) - 'a'))
          else None
        }
      idx.flatMap(q.options.lift).map(opt => (q.id, opt.id))
    }

  private def digitIndex(key: String): Option[Int] =
    if key.length == 1 && key(0) >= '1' && key(0) <= '9' then Some(key(0) - '1') else None

  enum SendKey:
    case Send, Newline, Ignore

  def sendOnKey(
      key: String,
      shift: Boolean,
      ctrlOrMeta: Boolean,
      ctrlEnterToSend: Boolean,
  ): SendKey =
    if key != "Enter" then SendKey.Ignore
    else if ctrlOrMeta then SendKey.Send
    else if shift then SendKey.Newline
    else if ctrlEnterToSend then SendKey.Newline
    else SendKey.Send
end ComposerQuery
