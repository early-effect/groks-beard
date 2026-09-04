package groksbeard.core

import zio.json.ast.Json

object SessionUpdate:
  def hostMsgs(params: Json, turnId: String): List[HostMsg] =
    SessionState.decodeUpdate(params) match
      case Some(AcpUpdate.Thought(content)) =>
        textOf(content).filter(_.nonEmpty).toList.map(t => HostMsg.ThoughtChunk(turnId, t))
      case Some(AcpUpdate.Agent(content)) =>
        textOf(content).filter(_.nonEmpty).toList.map(t => HostMsg.AgentChunk(turnId, t))
      case Some(AcpUpdate.Commands(commands)) =>
        List(HostMsg.AvailableCommands(commands))
      case Some(call: AcpUpdate.ToolCall) =>
        List(HostMsg.ToolGroup(turnId, List(toolRow(toBody(call)))))
      case Some(call: AcpUpdate.ToolCallUpdate) =>
        List(HostMsg.ToolGroup(turnId, List(toolRow(toBody(call)))))
      case Some(AcpUpdate.CurrentMode(modeId, currentModeId)) =>
        val mode = modeId.orElse(currentModeId).getOrElse("")
        if mode.isEmpty then Nil else List(HostMsg.SessionMeta("", "", mode, Nil))
      case None => Nil

  private def textOf(content: AcpContent): Option[String] =
    content match
      case AcpContent.Text(text) => Some(text)
      case _                     => None

  private def toBody(call: AcpUpdate.ToolCall): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

  private def toBody(call: AcpUpdate.ToolCallUpdate): AcpToolCall =
    AcpToolCall(call.toolCallId, call.title, call.kind, call.status, call.content, call.rawInput, call.locations)

  private def toolRow(toolCall: AcpToolCall): ToolRow =
    val extracted = DiffContent.diffsFromToolCall(toolCall.asJson)
    val stats     = extracted.diffs.headOption.map(d => ChangeSet.lineDiffStats(d.oldText, d.newText))
    ToolRow(
      extracted.toolCallId,
      extracted.title,
      extracted.kind,
      extracted.status,
      additions = stats.map(_._1),
      deletions = stats.map(_._2),
      input = extracted.diffs.headOption.map(_.path),
    )
  end toolRow
end SessionUpdate
