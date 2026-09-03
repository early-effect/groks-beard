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
end HostMsg

enum WebviewMsg:
  case Ready
  case Send(text: String)
  case SlashPick(name: String)
  case MentionQuery(query: String)
  case MentionPick(path: String, absPath: String)
  case CycleMode
  case SetMode(modeId: String)
  case OpenSettings
  case SetSetting(key: String, value: String | Boolean)
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
          case Some(Json.Str(other)) => Left(s"unknown WebviewMsg _tag: $other")
          case _                     => Left("WebviewMsg missing _tag")
      case _ => Left("WebviewMsg must be an object")
    }
end WebviewMsg
