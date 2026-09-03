package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class SettingsState(
    cliPath: String,
    nodePath: String,
    includeActiveFileByDefault: Boolean,
    useCtrlEnterToSend: Boolean,
    changesPresentation: String,
)

object SettingsState:
  val defaults: SettingsState =
    SettingsState("", "", includeActiveFileByDefault = true, useCtrlEnterToSend = false, "toast")

enum HostMsg:
  case Ready
  case SessionMeta(
      sessionId: String,
      title: String,
      modeId: String,
      availableModes: List[ModeOption] = Nil,
  )
  case AvailableCommands(commands: List[SlashCommand])
  case MentionResults(query: String, files: List[MentionFile])
  case Settings(state: SettingsState)
  case ComposerChip(path: String, absPath: String, source: String)
  case UserMessage(turnId: String, text: String, chips: List[PromptChip] = Nil, steer: Boolean = false)
  case AgentChunk(turnId: String, text: String, messageId: Option[String] = None)
  case ThoughtChunk(turnId: String, text: String)
  case ToolGroup(turnId: String, tools: List[ToolRow])
  case Permission(card: PermissionCard)
  case Plan(card: PlanCard)
  case Question(card: QuestionCard)
  case Elicit(card: ElicitCard)
  case TurnEnd(turnId: String, stopReason: String)
  case Queued(count: Int)
  case Changes(summary: ChangesSummary)
  case Error(message: String, code: Option[String] = None)
  case ClearTranscript
end HostMsg

object HostMsg:
  given JsonEncoder[HostMsg] =
    JsonEncoder[Json].contramap {
      case HostMsg.Ready =>
        Json.Obj("_tag" -> Json.Str("ready"))
      case HostMsg.SessionMeta(sessionId, title, modeId, modes) =>
        Json.Obj(
          "_tag"           -> Json.Str("sessionMeta"),
          "sessionId"      -> Json.Str(sessionId),
          "title"          -> Json.Str(title),
          "modeId"         -> Json.Str(modeId),
          "availableModes" -> Json.Arr(
            modes.map(mode => Json.Obj("id" -> Json.Str(mode.id), "name" -> Json.Str(mode.name)))*
          ),
        )
      case HostMsg.AvailableCommands(commands) =>
        Json.Obj(
          "_tag"     -> Json.Str("availableCommands"),
          "commands" -> Json.Arr(
            commands.map(c => Json.Obj("name" -> Json.Str(c.name), "description" -> Json.Str(c.description)))*
          ),
        )
      case HostMsg.MentionResults(query, files) =>
        Json.Obj(
          "_tag"  -> Json.Str("mentionResults"),
          "query" -> Json.Str(query),
          "files" -> Json.Arr(files.map(f => Json.Obj("path" -> Json.Str(f.path), "absPath" -> Json.Str(f.absPath)))*),
        )
      case HostMsg.Settings(state) =>
        Json.Obj(
          "_tag"                       -> Json.Str("settingsState"),
          "cliPath"                    -> Json.Str(state.cliPath),
          "nodePath"                   -> Json.Str(state.nodePath),
          "includeActiveFileByDefault" -> Json.Bool(state.includeActiveFileByDefault),
          "useCtrlEnterToSend"         -> Json.Bool(state.useCtrlEnterToSend),
          "changesPresentation"        -> Json.Str(state.changesPresentation),
        )
      case HostMsg.ComposerChip(path, absPath, source) =>
        Json.Obj(
          "_tag"    -> Json.Str("composerChip"),
          "path"    -> Json.Str(path),
          "absPath" -> Json.Str(absPath),
          "source"  -> Json.Str(source),
        )
      case HostMsg.UserMessage(turnId, text, chips, steer) =>
        Json.Obj(
          "_tag"   -> Json.Str("userMessage"),
          "turnId" -> Json.Str(turnId),
          "text"   -> Json.Str(text),
          "steer"  -> Json.Bool(steer),
          "chips"  -> Json.Arr(chips.map(chipJson)*),
        )
      case HostMsg.AgentChunk(turnId, text, messageId) =>
        jsonObj(
          List(
            "_tag"   -> Json.Str("agentChunk"),
            "turnId" -> Json.Str(turnId),
            "text"   -> Json.Str(text),
          ) ++ optional("messageId", messageId)
        )
      case HostMsg.ThoughtChunk(turnId, text) =>
        Json.Obj("_tag" -> Json.Str("thoughtChunk"), "turnId" -> Json.Str(turnId), "text" -> Json.Str(text))
      case HostMsg.ToolGroup(turnId, tools) =>
        Json.Obj(
          "_tag"   -> Json.Str("toolGroup"),
          "turnId" -> Json.Str(turnId),
          "tools"  -> Json.Arr(tools.map(toolJson)*),
        )
      case HostMsg.Permission(card) =>
        Json.Obj(
          "_tag"       -> Json.Str("permissionCard"),
          "requestId"  -> Json.Str(card.requestId),
          "toolCallId" -> Json.Str(card.toolCallId),
          "title"      -> Json.Str(card.title),
          "hasDiff"    -> Json.Bool(card.hasDiff),
          "options"    -> Json.Arr(
            card.options.map(o =>
              Json.Obj("optionId" -> Json.Str(o.optionId), "name" -> Json.Str(o.name), "kind" -> Json.Str(o.kind))
            )*
          ),
        )
      case HostMsg.Plan(card) =>
        Json.Obj(
          "_tag"         -> Json.Str("planCard"),
          "requestId"    -> Json.Str(card.requestId),
          "planMarkdown" -> Json.Str(card.planMarkdown),
        )
      case HostMsg.Question(card) =>
        Json.Obj(
          "_tag"      -> Json.Str("questionCard"),
          "requestId" -> Json.Str(card.requestId),
          "questions" -> Json.Arr(card.questions.map(questionJson)*),
        )
      case HostMsg.Elicit(card) =>
        jsonObj(
          List(
            "_tag"       -> Json.Str("elicitCard"),
            "requestId"  -> Json.Str(card.requestId),
            "serverName" -> Json.Str(card.serverName),
            "mode"       -> Json.Str(card.mode),
            "title"      -> Json.Str(card.title),
          ) ++ optional("url", card.url)
        )
      case HostMsg.TurnEnd(turnId, stopReason) =>
        Json.Obj("_tag" -> Json.Str("turnEnd"), "turnId" -> Json.Str(turnId), "stopReason" -> Json.Str(stopReason))
      case HostMsg.Queued(count) =>
        Json.Obj("_tag" -> Json.Str("queued"), "count" -> Json.Num(count))
      case HostMsg.Changes(summary) =>
        Json.Obj(
          "_tag"      -> Json.Str("changesSummary"),
          "fileCount" -> Json.Num(summary.fileCount),
          "additions" -> Json.Num(summary.additions),
          "deletions" -> Json.Num(summary.deletions),
        )
      case HostMsg.Error(message, code) =>
        jsonObj(List("_tag" -> Json.Str("error"), "message" -> Json.Str(message)) ++ optional("code", code))
      case HostMsg.ClearTranscript =>
        Json.Obj("_tag" -> Json.Str("clearTranscript"))
    }

  given JsonDecoder[HostMsg] =
    JsonDecoder[Json].mapOrFail {
      case obj: Json.Obj =>
        obj.get("_tag") match
          case Some(Json.Str("ready"))       => Right(HostMsg.Ready)
          case Some(Json.Str("sessionMeta")) =>
            for
              sessionId <- stringField(obj, "sessionId")
              title     <- stringField(obj, "title")
              modeId    <- stringField(obj, "modeId")
            yield HostMsg.SessionMeta(sessionId, title, modeId, modesField(obj))
          case Some(Json.Str("availableCommands")) =>
            Right(HostMsg.AvailableCommands(commandsField(obj)))
          case Some(Json.Str("mentionResults")) =>
            for query <- stringField(obj, "query")
            yield HostMsg.MentionResults(query, filesField(obj))
          case Some(Json.Str("settingsState")) =>
            for
              cliPath  <- stringField(obj, "cliPath")
              nodePath <- stringField(obj, "nodePath")
              include  <- boolField(obj, "includeActiveFileByDefault")
              ctrl     <- boolField(obj, "useCtrlEnterToSend")
              pres     <- stringField(obj, "changesPresentation")
            yield HostMsg.Settings(SettingsState(cliPath, nodePath, include, ctrl, pres))
          case Some(Json.Str("composerChip")) =>
            for
              path    <- stringField(obj, "path")
              absPath <- stringField(obj, "absPath")
              source  <- stringField(obj, "source")
            yield HostMsg.ComposerChip(path, absPath, source)
          case Some(Json.Str("userMessage")) =>
            for
              turnId <- stringField(obj, "turnId")
              text   <- stringField(obj, "text")
            yield HostMsg.UserMessage(turnId, text, chipsField(obj), boolField(obj, "steer").getOrElse(false))
          case Some(Json.Str("agentChunk")) =>
            for
              turnId <- stringField(obj, "turnId")
              text   <- stringField(obj, "text")
            yield HostMsg.AgentChunk(turnId, text, optionalString(obj, "messageId"))
          case Some(Json.Str("thoughtChunk")) =>
            for
              turnId <- stringField(obj, "turnId")
              text   <- stringField(obj, "text")
            yield HostMsg.ThoughtChunk(turnId, text)
          case Some(Json.Str("toolGroup")) =>
            for turnId <- stringField(obj, "turnId")
            yield HostMsg.ToolGroup(turnId, toolsField(obj))
          case Some(Json.Str("permissionCard")) =>
            for
              requestId  <- stringField(obj, "requestId")
              toolCallId <- stringField(obj, "toolCallId")
              title      <- stringField(obj, "title")
              hasDiff    <- boolField(obj, "hasDiff")
            yield HostMsg.Permission(PermissionCard(requestId, toolCallId, title, optionsField(obj), hasDiff))
          case Some(Json.Str("planCard")) =>
            for
              requestId <- stringField(obj, "requestId")
              markdown  <- stringField(obj, "planMarkdown")
            yield HostMsg.Plan(PlanCard(requestId, markdown))
          case Some(Json.Str("questionCard")) =>
            for requestId <- stringField(obj, "requestId")
            yield HostMsg.Question(QuestionCard(requestId, questionsField(obj)))
          case Some(Json.Str("elicitCard")) =>
            for
              requestId  <- stringField(obj, "requestId")
              serverName <- stringField(obj, "serverName")
              mode       <- stringField(obj, "mode")
              title      <- stringField(obj, "title")
            yield HostMsg.Elicit(ElicitCard(requestId, serverName, mode, title, optionalString(obj, "url")))
          case Some(Json.Str("turnEnd")) =>
            for
              turnId <- stringField(obj, "turnId")
              reason <- stringField(obj, "stopReason")
            yield HostMsg.TurnEnd(turnId, reason)
          case Some(Json.Str("queued")) =>
            intField(obj, "count").map(HostMsg.Queued.apply)
          case Some(Json.Str("changesSummary")) =>
            for
              files <- intField(obj, "fileCount")
              add   <- intField(obj, "additions")
              del   <- intField(obj, "deletions")
            yield HostMsg.Changes(ChangesSummary(files, add, del))
          case Some(Json.Str("error")) =>
            stringField(obj, "message").map(msg => HostMsg.Error(msg, optionalString(obj, "code")))
          case Some(Json.Str("clearTranscript")) =>
            Right(HostMsg.ClearTranscript)
          case Some(Json.Str(other)) => Left(s"unknown HostMsg _tag: $other")
          case _                     => Left("HostMsg missing _tag")
      case _ => Left("HostMsg must be an object")
    }

  private def stringField(obj: Json.Obj, key: String): Either[String, String] =
    obj.get(key) match
      case Some(Json.Str(value)) => Right(value)
      case _                     => Left(s"HostMsg.$key must be a string")

  private def boolField(obj: Json.Obj, key: String): Either[String, Boolean] =
    obj.get(key) match
      case Some(Json.Bool(value)) => Right(value)
      case _                      => Left(s"HostMsg.$key must be a boolean")

  private def modesField(obj: Json.Obj): List[ModeOption] =
    obj.get("availableModes") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("id"), o.get("name")) match
            case (Some(Json.Str(id)), Some(Json.Str(name))) => Some(ModeOption(id, name))
            case _                                          => None
        }.flatten
      case _ => Nil

  private def commandsField(obj: Json.Obj): List[SlashCommand] =
    obj.get("commands") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("name"), o.get("description")) match
            case (Some(Json.Str(name)), Some(Json.Str(desc))) => Some(SlashCommand(name, desc))
            case _                                            => None
        }.flatten
      case _ => Nil

  private def filesField(obj: Json.Obj): List[MentionFile] =
    obj.get("files") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("path"), o.get("absPath")) match
            case (Some(Json.Str(path)), Some(Json.Str(abs))) => Some(MentionFile(path, abs))
            case _                                           => None
        }.flatten
      case _ => Nil

  private def jsonObj(fields: List[(String, Json)]): Json =
    Json.Obj(fields*)

  private def optional(key: String, value: Option[String]): List[(String, Json)] =
    value.toList.map(v => key -> Json.Str(v))

  private def optionalString(obj: Json.Obj, key: String): Option[String] =
    obj.get(key) match
      case Some(Json.Str(value)) => Some(value)
      case _                     => None

  private def intField(obj: Json.Obj, key: String): Either[String, Int] =
    obj.get(key) match
      case Some(Json.Num(n)) => Right(n.intValue)
      case _                 => Left(s"HostMsg.$key must be a number")

  private def chipJson(chip: PromptChip): Json =
    jsonObj(
      List(
        "path"    -> Json.Str(chip.path),
        "absPath" -> Json.Str(chip.absPath),
        "source"  -> Json.Str(chip.source),
      ) ++ chip.startLine.toList.map(n => "startLine" -> Json.Num(n))
        ++ chip.endLine.toList.map(n => "endLine" -> Json.Num(n))
    )

  private def toolJson(row: ToolRow): Json =
    jsonObj(
      List(
        "id"     -> Json.Str(row.id),
        "title"  -> Json.Str(row.title),
        "kind"   -> Json.Str(row.kind),
        "status" -> Json.Str(row.status),
      ) ++ row.additions.toList.map(n => "additions" -> Json.Num(n))
        ++ row.deletions.toList.map(n => "deletions" -> Json.Num(n))
        ++ row.input.toList.map(s => "input" -> Json.Str(s))
        ++ row.output.toList.map(s => "output" -> Json.Str(s))
    )

  private def questionJson(q: AgentQuestion): Json =
    Json.Obj(
      "id"      -> Json.Str(q.id),
      "prompt"  -> Json.Str(q.prompt),
      "options" -> Json.Arr(
        q.options.map(o => Json.Obj("id" -> Json.Str(o.id), "label" -> Json.Str(o.label)))*
      ),
    )

  private def chipsField(obj: Json.Obj): List[PromptChip] =
    obj.get("chips") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("path"), o.get("absPath"), o.get("source")) match
            case (Some(Json.Str(path)), Some(Json.Str(abs)), Some(Json.Str(source))) =>
              Some(
                PromptChip(
                  path,
                  abs,
                  source,
                  intField(o, "startLine").toOption,
                  intField(o, "endLine").toOption,
                )
              )
            case _ => None
        }.flatten
      case _ => Nil

  private def toolsField(obj: Json.Obj): List[ToolRow] =
    obj.get("tools") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("id"), o.get("title"), o.get("kind"), o.get("status")) match
            case (Some(Json.Str(id)), Some(Json.Str(title)), Some(Json.Str(kind)), Some(Json.Str(status))) =>
              Some(
                ToolRow(
                  id,
                  title,
                  kind,
                  status,
                  intField(o, "additions").toOption,
                  intField(o, "deletions").toOption,
                  optionalString(o, "input"),
                  optionalString(o, "output"),
                )
              )
            case _ => None
        }.flatten
      case _ => Nil

  private def optionsField(obj: Json.Obj): List[PermissionOption] =
    obj.get("options") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("optionId"), o.get("name"), o.get("kind")) match
            case (Some(Json.Str(id)), Some(Json.Str(name)), Some(Json.Str(kind))) =>
              Some(PermissionOption(id, name, kind))
            case _ => None
        }.flatten
      case _ => Nil

  private def questionsField(obj: Json.Obj): List[AgentQuestion] =
    obj.get("questions") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("id"), o.get("prompt")) match
            case (Some(Json.Str(id)), Some(Json.Str(prompt))) =>
              Some(AgentQuestion(id, prompt, questionOptions(o)))
            case _ => None
        }.flatten
      case _ => Nil

  private def questionOptions(obj: Json.Obj): List[QuestionOption] =
    obj.get("options") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          (o.get("id"), o.get("label")) match
            case (Some(Json.Str(id)), Some(Json.Str(label))) => Some(QuestionOption(id, label))
            case _                                           => None
        }.flatten
      case _ => Nil
end HostMsg

enum WebviewMsg:
  case Ready
  case Send(text: String)
  case Queue(text: String)
  case Cancel
  case SlashPick(name: String)
  case MentionQuery(query: String)
  case MentionPick(path: String, absPath: String)
  case CycleMode
  case SetMode(modeId: String)
  case OpenSettings
  case SetSetting(key: String, value: String | Boolean)
  case PermissionChoice(requestId: String, optionId: String)
  case PermissionPark(requestId: String)
  case OpenDiff(requestId: String)
  case PlanVerdict(requestId: String, verdict: String)
  case QuestionChoice(requestId: String, questionId: String, optionId: String)
  case QuestionDismiss(requestId: String)
  case ElicitAccept(requestId: String)
  case ElicitDecline(requestId: String)
  case OpenChanges
end WebviewMsg

object WebviewMsg:
  given JsonEncoder[WebviewMsg] =
    JsonEncoder[Json].contramap {
      case WebviewMsg.Ready             => Json.Obj("_tag" -> Json.Str("ready"))
      case WebviewMsg.Send(text)        => Json.Obj("_tag" -> Json.Str("send"), "text" -> Json.Str(text))
      case WebviewMsg.SlashPick(name)   => Json.Obj("_tag" -> Json.Str("slashPick"), "name" -> Json.Str(name))
      case WebviewMsg.MentionQuery(q)   => Json.Obj("_tag" -> Json.Str("mentionQuery"), "query" -> Json.Str(q))
      case WebviewMsg.MentionPick(p, a) =>
        Json.Obj("_tag" -> Json.Str("mentionPick"), "path" -> Json.Str(p), "absPath" -> Json.Str(a))
      case WebviewMsg.CycleMode        => Json.Obj("_tag" -> Json.Str("cycleMode"))
      case WebviewMsg.SetMode(id)      => Json.Obj("_tag" -> Json.Str("setMode"), "modeId" -> Json.Str(id))
      case WebviewMsg.OpenSettings     => Json.Obj("_tag" -> Json.Str("openSettings"))
      case WebviewMsg.SetSetting(k, v) =>
        val value = v match
          case s: String  => Json.Str(s)
          case b: Boolean => Json.Bool(b)
        Json.Obj("_tag" -> Json.Str("setSetting"), "key" -> Json.Str(k), "value" -> value)
      case WebviewMsg.Queue(text) =>
        Json.Obj("_tag" -> Json.Str("queue"), "text" -> Json.Str(text))
      case WebviewMsg.Cancel =>
        Json.Obj("_tag" -> Json.Str("cancel"))
      case WebviewMsg.PermissionChoice(requestId, optionId) =>
        Json.Obj(
          "_tag"      -> Json.Str("permissionChoice"),
          "requestId" -> Json.Str(requestId),
          "optionId"  -> Json.Str(optionId),
        )
      case WebviewMsg.PermissionPark(requestId) =>
        Json.Obj("_tag" -> Json.Str("permissionPark"), "requestId" -> Json.Str(requestId))
      case WebviewMsg.OpenDiff(requestId) =>
        Json.Obj("_tag" -> Json.Str("openDiff"), "requestId" -> Json.Str(requestId))
      case WebviewMsg.PlanVerdict(requestId, verdict) =>
        Json.Obj(
          "_tag"      -> Json.Str("planVerdict"),
          "requestId" -> Json.Str(requestId),
          "verdict"   -> Json.Str(verdict),
        )
      case WebviewMsg.QuestionChoice(requestId, questionId, optionId) =>
        Json.Obj(
          "_tag"       -> Json.Str("questionChoice"),
          "requestId"  -> Json.Str(requestId),
          "questionId" -> Json.Str(questionId),
          "optionId"   -> Json.Str(optionId),
        )
      case WebviewMsg.QuestionDismiss(requestId) =>
        Json.Obj("_tag" -> Json.Str("questionDismiss"), "requestId" -> Json.Str(requestId))
      case WebviewMsg.ElicitAccept(requestId) =>
        Json.Obj("_tag" -> Json.Str("elicitAccept"), "requestId" -> Json.Str(requestId))
      case WebviewMsg.ElicitDecline(requestId) =>
        Json.Obj("_tag" -> Json.Str("elicitDecline"), "requestId" -> Json.Str(requestId))
      case WebviewMsg.OpenChanges =>
        Json.Obj("_tag" -> Json.Str("openChanges"))
    }

  given JsonDecoder[WebviewMsg] =
    JsonDecoder[Json].mapOrFail {
      case obj: Json.Obj =>
        obj.get("_tag") match
          case Some(Json.Str("ready")) => Right(WebviewMsg.Ready)
          case Some(Json.Str("send"))  =>
            obj.get("text") match
              case Some(Json.Str(text)) => Right(WebviewMsg.Send(text))
              case _                    => Left("WebviewMsg.send missing text")
          case Some(Json.Str("slashPick")) =>
            obj.get("name") match
              case Some(Json.Str(name)) => Right(WebviewMsg.SlashPick(name))
              case _                    => Left("WebviewMsg.slashPick missing name")
          case Some(Json.Str("mentionQuery")) =>
            obj.get("query") match
              case Some(Json.Str(q)) => Right(WebviewMsg.MentionQuery(q))
              case _                 => Left("WebviewMsg.mentionQuery missing query")
          case Some(Json.Str("mentionPick")) =>
            (obj.get("path"), obj.get("absPath")) match
              case (Some(Json.Str(p)), Some(Json.Str(a))) => Right(WebviewMsg.MentionPick(p, a))
              case _                                      => Left("WebviewMsg.mentionPick missing path")
          case Some(Json.Str("cycleMode")) => Right(WebviewMsg.CycleMode)
          case Some(Json.Str("setMode"))   =>
            obj.get("modeId") match
              case Some(Json.Str(id)) => Right(WebviewMsg.SetMode(id))
              case _                  => Left("WebviewMsg.setMode missing modeId")
          case Some(Json.Str("openSettings")) => Right(WebviewMsg.OpenSettings)
          case Some(Json.Str("setSetting"))   =>
            (obj.get("key"), obj.get("value")) match
              case (Some(Json.Str(k)), Some(Json.Str(s)))  => Right(WebviewMsg.SetSetting(k, s))
              case (Some(Json.Str(k)), Some(Json.Bool(b))) => Right(WebviewMsg.SetSetting(k, b))
              case _                                       => Left("WebviewMsg.setSetting missing key/value")
          case Some(Json.Str("queue")) =>
            obj.get("text") match
              case Some(Json.Str(text)) => Right(WebviewMsg.Queue(text))
              case _                    => Left("WebviewMsg.queue missing text")
          case Some(Json.Str("cancel"))           => Right(WebviewMsg.Cancel)
          case Some(Json.Str("permissionChoice")) =>
            (obj.get("requestId"), obj.get("optionId")) match
              case (Some(Json.Str(r)), Some(Json.Str(o))) => Right(WebviewMsg.PermissionChoice(r, o))
              case _                                      => Left("WebviewMsg.permissionChoice missing fields")
          case Some(Json.Str("permissionPark")) =>
            obj.get("requestId") match
              case Some(Json.Str(r)) => Right(WebviewMsg.PermissionPark(r))
              case _                 => Left("WebviewMsg.permissionPark missing requestId")
          case Some(Json.Str("openDiff")) =>
            obj.get("requestId") match
              case Some(Json.Str(r)) => Right(WebviewMsg.OpenDiff(r))
              case _                 => Left("WebviewMsg.openDiff missing requestId")
          case Some(Json.Str("planVerdict")) =>
            (obj.get("requestId"), obj.get("verdict")) match
              case (Some(Json.Str(r)), Some(Json.Str(v))) => Right(WebviewMsg.PlanVerdict(r, v))
              case _                                      => Left("WebviewMsg.planVerdict missing fields")
          case Some(Json.Str("questionChoice")) =>
            (obj.get("requestId"), obj.get("questionId"), obj.get("optionId")) match
              case (Some(Json.Str(r)), Some(Json.Str(q)), Some(Json.Str(o))) =>
                Right(WebviewMsg.QuestionChoice(r, q, o))
              case _ => Left("WebviewMsg.questionChoice missing fields")
          case Some(Json.Str("questionDismiss")) =>
            obj.get("requestId") match
              case Some(Json.Str(r)) => Right(WebviewMsg.QuestionDismiss(r))
              case _                 => Left("WebviewMsg.questionDismiss missing requestId")
          case Some(Json.Str("elicitAccept")) =>
            obj.get("requestId") match
              case Some(Json.Str(r)) => Right(WebviewMsg.ElicitAccept(r))
              case _                 => Left("WebviewMsg.elicitAccept missing requestId")
          case Some(Json.Str("elicitDecline")) =>
            obj.get("requestId") match
              case Some(Json.Str(r)) => Right(WebviewMsg.ElicitDecline(r))
              case _                 => Left("WebviewMsg.elicitDecline missing requestId")
          case Some(Json.Str("openChanges")) => Right(WebviewMsg.OpenChanges)
          case Some(Json.Str(other))         => Left(s"unknown WebviewMsg _tag: $other")
          case _                             => Left("WebviewMsg missing _tag")
      case _ => Left("WebviewMsg must be an object")
    }
end WebviewMsg
