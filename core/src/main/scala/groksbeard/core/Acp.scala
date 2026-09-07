package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class ClientInfo(name: String, title: String, version: String) derives JsonCodec
final case class FsCaps(readTextFile: Boolean = true) derives JsonCodec
final case class SessionCaps(configOptions: EmptyObject = EmptyObject()) derives JsonCodec
final case class ClientCapabilities(
    fs: Option[FsCaps] = None,
    terminal: Option[Boolean] = None,
    session: Option[SessionCaps] = Some(SessionCaps()),
) derives JsonCodec

object ClientCapabilities:
  val FsReadFloorMajor = 1
  val FsReadFloorMinor = 0
  val FsReadFloorPatch = 4

  val fake: ClientCapabilities =
    ClientCapabilities(fs = Some(FsCaps(true)), terminal = None)

  def forSpawn(version: Option[GrokVersion], verified: Boolean, terminalHandlersReady: Boolean): ClientCapabilities =
    val fs =
      if verified && version.exists(v => GrokVersion.isAtLeast(v, FsReadFloorMajor, FsReadFloorMinor, FsReadFloorPatch))
      then None
      else Some(FsCaps(true))
    val terminal = if terminalHandlersReady then Some(true) else None
    ClientCapabilities(fs = fs, terminal = terminal)
end ClientCapabilities

final case class InitializeParams(
    protocolVersion: Int,
    clientCapabilities: ClientCapabilities = ClientCapabilities.fake,
    clientInfo: ClientInfo = ClientInfo("groks-beard", "Grok's Beard", "0.2.0"),
) derives JsonCodec
final case class AgentCapabilities(loadSession: Boolean = false) derives JsonCodec
final case class InitializeResult(protocolVersion: Int, agentCapabilities: AgentCapabilities) derives JsonCodec

final case class SessionNewParams(cwd: String, mcpServers: List[Json] = Nil) derives JsonCodec
final case class SessionModeState(currentModeId: ModeId, availableModes: List[ModeOption] = Nil) derives JsonCodec
final case class SessionModelState(currentModelId: ModelId, availableModels: List[ModelOption] = Nil) derives JsonCodec
final case class SessionNewResult(
    sessionId: SessionId,
    modes: Option[SessionModeState] = None,
    models: Option[SessionModelState] = None,
    _meta: Option[Json] = None,
) derives JsonCodec
final case class SetModelMeta(reasoningEffort: Option[String] = None) derives JsonCodec
final case class SessionSetModelParams(
    sessionId: SessionId,
    modelId: ModelId,
    _meta: Option[SetModelMeta] = None,
) derives JsonCodec
final case class SessionLoadParams(sessionId: SessionId, cwd: String = ".", mcpServers: List[Json] = Nil)
    derives JsonCodec
final case class SessionLoadResult(sessionId: SessionId) derives JsonCodec
final case class SessionSetModeParams(sessionId: SessionId, modeId: ModeId) derives JsonCodec
final case class SessionCancelParams(sessionId: SessionId) derives JsonCodec

final case class PromptText(@jsonField("type") tpe: String = "text", text: String) derives JsonCodec
final case class SessionPromptParams(sessionId: SessionId, prompt: List[PromptText]) derives JsonCodec
final case class SessionPromptResult(stopReason: StopReason) derives JsonCodec

@jsonDiscriminator("type")
enum AcpContent derives JsonCodec:
  @jsonHint("text") case Text(text: String)
  @jsonHint("diff") case Diff(path: String, oldText: Option[String] = None, newText: Option[String] = None)
  @jsonHint("content") case Block(content: AcpContent)
  @jsonHint("terminal") case Terminal(terminalId: String)

final case class AcpToolCall(
    toolCallId: ToolCallId = ToolCallId("tool"),
    title: String = "Tool",
    kind: ToolKind = ToolKind.Other,
    status: ToolStatus = ToolStatus.Pending,
    content: List[AcpContent] = Nil,
    rawInput: Option[Json] = None,
    locations: List[ToolLocation] = Nil,
    rawOutput: Option[Json] = None,
) derives JsonCodec

@jsonDiscriminator("sessionUpdate")
enum AcpUpdate derives JsonCodec:
  @jsonHint("agent_thought_chunk") case Thought(content: AcpContent)
  @jsonHint("agent_message_chunk") case Agent(content: AcpContent)
  @jsonHint("user_message_chunk") case User(content: AcpContent)
  @jsonHint("available_commands_update") case Commands(availableCommands: List[SlashCommand] = Nil)
  @jsonHint("tool_call") case ToolCall(
      toolCallId: ToolCallId = ToolCallId("tool"),
      title: String = "Tool",
      kind: ToolKind = ToolKind.Other,
      status: ToolStatus = ToolStatus.Pending,
      content: List[AcpContent] = Nil,
      rawInput: Option[Json] = None,
      locations: List[ToolLocation] = Nil,
      rawOutput: Option[Json] = None,
  )
  @jsonHint("tool_call_update") case ToolCallUpdate(
      toolCallId: ToolCallId = ToolCallId("tool"),
      title: String = "",
      kind: ToolKind = ToolKind.Other,
      status: ToolStatus = ToolStatus.Pending,
      content: List[AcpContent] = Nil,
      rawInput: Option[Json] = None,
      locations: List[ToolLocation] = Nil,
      rawOutput: Option[Json] = None,
  )
  @jsonHint("current_mode_update") case CurrentMode(
      modeId: Option[ModeId] = None,
      currentModeId: Option[ModeId] = None,
  )
  @jsonHint("usage_update") case Usage(used: Option[Int] = None, size: Option[Int] = None)
  @jsonHint("plan") case Plan(entries: List[TodoEntry] = Nil)
end AcpUpdate

final case class AcpSessionNotify(sessionId: SessionId = SessionId.empty, update: AcpUpdate) derives JsonCodec

final case class PermissionRequestParams(
    toolCall: AcpToolCall = AcpToolCall(),
    options: List[PermissionOption] = Nil,
) derives JsonCodec

final case class AskUserQuestionParams(questions: List[AgentQuestion] = Nil) derives JsonCodec

final case class RawEditInput(
    path: Option[String] = None,
    old_string: Option[String] = None,
    oldText: Option[String] = None,
    new_string: Option[String] = None,
    newText: Option[String] = None,
    contents: Option[String] = None,
    replace_all: Option[Boolean] = None,
    from_path: Option[String] = None,
    fromPath: Option[String] = None,
    from: Option[String] = None,
    destination: Option[String] = None,
    source: Option[String] = None,
) derives JsonCodec
