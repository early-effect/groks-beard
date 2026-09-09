package groksbeard.core

import zio.json.*
import zio.json.ast.Json

object SessionUpdate:
  def isSessionNotify(method: String): Boolean =
    AcpMethod.isSessionNotify(method)

  def hostMsgs(params: Json, turnId: TurnId): List[HostMsg] =
    SessionState.decodeUpdate(params) match
      case Some(_: AcpUpdate.ConfigOptions) => Nil
      case Some(AcpUpdate.Thought(content)) =>
        textOf(content).filter(_.nonEmpty).toList.map(t => HostMsg.ThoughtChunk(turnId, t))
      case Some(AcpUpdate.Agent(content)) =>
        textOf(content).filter(_.nonEmpty).toList.map(t => HostMsg.AgentChunk(turnId, t))
      case Some(AcpUpdate.User(content)) =>
        textOf(content).filter(_.nonEmpty).toList.map(t => HostMsg.UserMessage(turnId, t))
      case Some(AcpUpdate.Commands(commands)) =>
        List(HostMsg.AvailableCommands(commands))
      case Some(call: AcpUpdate.ToolCall) =>
        toolMsgs(turnId, toBody(call))
      case Some(call: AcpUpdate.ToolCallUpdate) =>
        toolMsgs(turnId, toBody(call))
      case Some(AcpUpdate.CurrentMode(modeId, currentModeId)) =>
        val mode = modeId.orElse(currentModeId).getOrElse(ModeId.empty)
        if mode.isEmpty then Nil else List(HostMsg.SessionMeta(SessionId.empty, "", mode, Nil))
      case Some(AcpUpdate.Usage(used, size)) =>
        occupancyMsg(used, size).orElse(Occupancy.fromJson(params)).toList.map { occ =>
          HostMsg.SessionMeta(SessionId.empty, "", ModeId.empty, occupancy = Some(occ))
        }
      case Some(AcpUpdate.Plan(entries)) =>
        List(HostMsg.Todos(Todos.fromEntries(entries)))
      case None => Nil

  private def occupancyMsg(used: Option[Int], size: Option[Int]): Option[Occupancy] =
    (used, size) match
      case (Some(u), Some(s)) if s > 0 => Some(Occupancy(u, s))
      case _                           => None

  private def textOf(content: AcpContent): Option[String] =
    content match
      case AcpContent.Text(text) => Some(text)
      case _                     => None

  private def toBody(call: AcpUpdate.ToolCall): AcpToolCall =
    AcpToolCall(
      call.toolCallId,
      call.title,
      call.kind,
      call.status,
      call.content,
      call.rawInput,
      call.locations,
      call.rawOutput,
    )

  private def toBody(call: AcpUpdate.ToolCallUpdate): AcpToolCall =
    AcpToolCall(
      call.toolCallId,
      call.title,
      call.kind,
      call.status,
      call.content,
      call.rawInput,
      call.locations,
      call.rawOutput,
    )

  private def toolMsgs(turnId: TurnId, toolCall: AcpToolCall): List[HostMsg] =
    val row = toolRow(toolCall)
    val out = ToolView.outputOf(toolCall)
    HostMsg.ToolCall(turnId, row) :: out.toList.map(t => HostMsg.ToolChunk(turnId, row.id, t, snapshot = true))

  private def toolRow(toolCall: AcpToolCall): ToolRow =
    val extracted = DiffContent.diffsFromToolCall(toolCall.asJson)
    val stats     = extracted.diffs.headOption.map(d => ChangeSet.lineDiffStats(d.oldText, d.newText))
    val loc       = FollowAlong.pick(toolCall.locations, extracted.toolCallId)
    ToolRow(
      extracted.toolCallId,
      extracted.title,
      extracted.kind,
      extracted.status,
      additions = stats.map(_._1),
      deletions = stats.map(_._2),
      input = ToolView.inputOf(toolCall, extracted.diffs),
      path = loc.map(_.path),
      line = loc.flatMap(_.line),
    )
  end toolRow
end SessionUpdate
