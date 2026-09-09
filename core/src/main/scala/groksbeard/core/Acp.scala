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
final case class PromptCapabilities(
    image: Boolean = false,
    audio: Boolean = false,
    embeddedContext: Boolean = false,
) derives JsonCodec
final case class AgentCapabilities(
    loadSession: Boolean = false,
    promptCapabilities: Option[PromptCapabilities] = None,
    sessionCapabilities: Option[Json] = None,
    _meta: Option[Json] = None,
) derives JsonCodec
final case class InitializeResult(protocolVersion: Int, agentCapabilities: AgentCapabilities) derives JsonCodec

object AgentCapabilities:
  def offersSession(caps: AgentCapabilities, method: String): Boolean =
    caps.sessionCapabilities match
      case Some(obj: Json.Obj) =>
        obj.fields.exists { (k, v) =>
          k == method && (v match
            case Json.Bool(false) => false
            case Json.Null        => false
            case _                => true)
        }
      case _ => false

  def embedded(caps: AgentCapabilities): Boolean =
    caps.promptCapabilities.exists(_.embeddedContext)

  def image(caps: AgentCapabilities): Boolean =
    caps.promptCapabilities.exists(_.image)
end AgentCapabilities

final case class SessionNewParams(cwd: String, mcpServers: List[Json] = Nil, _meta: Option[Json] = None)
    derives JsonCodec
final case class SessionModeState(currentModeId: ModeId, availableModes: List[ModeOption] = Nil) derives JsonCodec
final case class SessionModelState(currentModelId: ModelId, availableModels: List[ModelOption] = Nil) derives JsonCodec
final case class SessionNewResult(
    sessionId: SessionId,
    modes: Option[SessionModeState] = None,
    models: Option[SessionModelState] = None,
    configOptions: List[ConfigOption] = Nil,
    _meta: Option[Json] = None,
) derives JsonCodec
final case class SetModelMeta(reasoningEffort: Option[String] = None) derives JsonCodec
final case class SessionSetModelParams(
    sessionId: SessionId,
    modelId: ModelId,
    _meta: Option[SetModelMeta] = None,
) derives JsonCodec
final case class SessionLoadParams(
    sessionId: SessionId,
    cwd: String = ".",
    mcpServers: List[Json] = Nil,
    _meta: Option[Json] = None,
) derives JsonCodec
final case class SessionLoadResult(sessionId: SessionId, configOptions: List[ConfigOption] = Nil) derives JsonCodec
final case class SessionSetModeParams(sessionId: SessionId, modeId: ModeId) derives JsonCodec
final case class SessionCancelParams(sessionId: SessionId) derives JsonCodec
final case class SessionListParams(cwd: Option[String] = None, cursor: Option[String] = None) derives JsonCodec
final case class SessionCloseParams(sessionId: SessionId) derives JsonCodec
final case class SessionResumeParams(
    sessionId: SessionId,
    cwd: String = ".",
    mcpServers: List[Json] = Nil,
) derives JsonCodec

final case class PromptText(@jsonField("type") tpe: String = "text", text: String) derives JsonCodec
final case class SessionPromptParams(sessionId: SessionId, prompt: List[PromptBlock]) derives JsonCodec

final case class ForkSessionParams(
    sourceSessionId: SessionId,
    sourceCwd: String,
    newCwd: String,
    newSessionId: Option[String] = None,
    newModelId: Option[String] = None,
    targetPromptIndex: Option[Int] = None,
    sessionKind: Option[String] = None,
    sourceWorkspaceDir: Option[String] = None,
) derives JsonCodec

final case class ForkSessionResult(
    newSessionId: SessionId,
    chatMessagesCopied: Int = 0,
    updatesCopied: Int = 0,
    planStateCopied: Boolean = false,
    newCwd: String = "",
    parentSessionId: SessionId = SessionId.empty,
    newModelId: Option[String] = None,
) derives JsonCodec
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
  @jsonHint("config_option_update") case ConfigOptions(configOptions: List[ConfigOption] = Nil)
  @jsonHint("turn_completed") case TurnCompleted(
      @jsonField("stop_reason") stopReason: StopReason = StopReason.EndTurn
  )
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
