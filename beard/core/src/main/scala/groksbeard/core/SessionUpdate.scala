package groksbeard.core

import zio.json.ast.Json

object SessionUpdate:
  def hostMsgs(params: Json, turnId: String): List[HostMsg] =
    val update = SessionState.unwrapUpdate(params)
    update.get("sessionUpdate") match
      case Some(Json.Str("agent_thought_chunk")) =>
        textFromContent(update).filter(_.nonEmpty).toList.map(t => HostMsg.ThoughtChunk(turnId, t))
      case Some(Json.Str("agent_message_chunk")) =>
        textFromContent(update).filter(_.nonEmpty).toList.map(t => HostMsg.AgentChunk(turnId, t))
      case Some(Json.Str("available_commands_update")) =>
        List(HostMsg.AvailableCommands(commands(update)))
      case Some(Json.Str("tool_call")) | Some(Json.Str("tool_call_update")) =>
        List(HostMsg.ToolGroup(turnId, List(toolRow(update))))
      case Some(Json.Str("current_mode_update")) =>
        val mode = (update.get("modeId") orElse update.get("currentModeId")) match
          case Some(Json.Str(id)) => id
          case _                  => ""
        if mode.isEmpty then Nil
        else List(HostMsg.SessionMeta("", "", mode, Nil))
      case _ => Nil
    end match
  end hostMsgs

  private def textFromContent(obj: Json.Obj): Option[String] =
    obj.get("content") match
      case Some(Json.Str(s)) => Some(s)
      case Some(c: Json.Obj) =>
        c.get("text") match
          case Some(Json.Str(s)) => Some(s)
          case _                 => None
      case Some(Json.Arr(items)) =>
        val texts = items.toList.flatMap {
          case o: Json.Obj =>
            o.get("text") match
              case Some(Json.Str(s)) => Some(s)
              case _                 => None
          case Json.Str(s) => Some(s)
          case _           => None
        }
        if texts.isEmpty then None else Some(texts.mkString)
      case _ => None

  private def commands(obj: Json.Obj): List[SlashCommand] =
    obj.get("availableCommands") match
      case Some(Json.Arr(items)) =>
        items.toList.collect { case o: Json.Obj =>
          o.get("name") match
            case Some(Json.Str(name)) =>
              val desc = o.get("description") match
                case Some(Json.Str(d)) => d
                case _                 => ""
              Some(SlashCommand(name, desc))
            case _ => None
        }.flatten
      case _ => Nil

  private def toolRow(obj: Json.Obj): ToolRow =
    val id = obj.get("toolCallId") match
      case Some(Json.Str(s)) => s
      case _                 => "tool"
    val title = obj.get("title") match
      case Some(Json.Str(s)) => s
      case _                 => ""
    val kind = obj.get("kind") match
      case Some(Json.Str(s)) => s
      case _                 => ""
    val status = obj.get("status") match
      case Some(Json.Str(s)) => s
      case _                 => "pending"
    ToolRow(id, title, kind, status)
  end toolRow
end SessionUpdate
