package groksbeard.core

import zio.json.*
import zio.json.ast.Json

given stringOrBooleanCodec: JsonCodec[String | Boolean] = JsonCodec(
  JsonEncoder[Json].contramap {
    case s: String  => Json.Str(s)
    case b: Boolean => Json.Bool(b)
  },
  JsonDecoder[Json].mapOrFail {
    case Json.Str(s)  => Right(s)
    case Json.Bool(b) => Right(b)
    case _            => Left("expected string or boolean")
  },
)

final case class SettingsState(
    cliPath: String,
    nodePath: String,
    includeActiveFileByDefault: Boolean,
    useCtrlEnterToSend: Boolean,
    changesPresentation: String,
) derives JsonCodec

object SettingsState:
  val defaults: SettingsState =
    SettingsState("", "", includeActiveFileByDefault = true, useCtrlEnterToSend = false, "toast")

@jsonDiscriminator("_tag")
enum HostMsg derives JsonCodec:
  @jsonHint("ready") case Ready
  @jsonHint("sessionMeta") case SessionMeta(
      sessionId: SessionId,
      title: String,
      modeId: ModeId,
      availableModes: List[ModeOption] = Nil,
      occupancy: Option[Occupancy] = None,
      modelId: ModelId = ModelId.empty,
      availableModels: List[ModelOption] = Nil,
      effort: String = "",
      cwd: String = "",
  )
  @jsonHint("sessionList") case SessionList(
      sessions: List[SessionRow],
      currentId: SessionId = SessionId.empty,
      openPicker: Boolean = false,
  )
  @jsonHint("sessionLocked") case SessionLocked(sessionId: SessionId, message: String)
  @jsonHint("availableCommands") case AvailableCommands(commands: List[SlashCommand])
  @jsonHint("mentionResults") case MentionResults(query: String, files: List[MentionFile])
  @jsonHint("settingsState") case Settings(
      cliPath: String,
      nodePath: String,
      includeActiveFileByDefault: Boolean,
      useCtrlEnterToSend: Boolean,
      changesPresentation: String,
  )
  @jsonHint("composerChip") case ComposerChip(
      path: String,
      absPath: String,
      source: ChipSource,
      startLine: Option[Int] = None,
      endLine: Option[Int] = None,
  )
  @jsonHint("userMessage") case UserMessage(
      turnId: TurnId,
      text: String,
      chips: List[PromptChip] = Nil,
      steer: Boolean = false,
  )
  @jsonHint("agentChunk") case AgentChunk(turnId: TurnId, text: String, messageId: Option[String] = None)
  @jsonHint("thoughtChunk") case ThoughtChunk(turnId: TurnId, text: String)
  @jsonHint("toolCall") case ToolCall(turnId: TurnId, tool: ToolRow)
  @jsonHint("toolChunk") case ToolChunk(
      turnId: TurnId,
      toolCallId: ToolCallId,
      text: String,
      snapshot: Boolean = false,
  )
  @jsonHint("permissionCard") case Permission(
      requestId: RequestId,
      toolCallId: ToolCallId,
      title: String,
      options: List[PermissionOption] = Nil,
      hasDiff: Boolean = false,
  )
  @jsonHint("planCard") case Plan(requestId: RequestId, planMarkdown: String)
  @jsonHint("questionCard") case Question(requestId: RequestId, questions: List[AgentQuestion])
  @jsonHint("elicitCard") case Elicit(
      requestId: RequestId,
      serverName: String,
      mode: ElicitMode,
      title: String,
      url: Option[String] = None,
  )
  @jsonHint("turnEnd") case TurnEnd(turnId: TurnId, stopReason: StopReason)
  @jsonHint("queued") case Queued(items: List[QueuedPrompt] = Nil)
  @jsonHint("changesSummary") case Changes(
      fileCount: Int,
      additions: Int,
      deletions: Int,
      files: List[ChangeFileView] = Nil,
  )
  @jsonHint("diffPreview") case DiffPreview(path: String, oldText: String, newText: String, wholeFile: Boolean = true)
  @jsonHint("clearDiff") case ClearDiff
  @jsonHint("error") case Error(message: String, code: Option[String] = None)
  @jsonHint("copied") case Copied(message: String, clipboard: Option[String] = None)
  @jsonHint("todos") case Todos(entries: List[TodoEntry] = Nil)
  @jsonHint("toggleTodos") case ToggleTodos
  @jsonHint("toggleQueue") case ToggleQueue
  @jsonHint("tasks") case Tasks(entries: List[TaskRow] = Nil)
  @jsonHint("toggleTasks") case ToggleTasks
  @jsonHint("taskNotice") case TaskNotice(text: String)
  @jsonHint("forkAsk") case ForkAsk(directive: String = "")
  @jsonHint("openPalette") case OpenPalette
  @jsonHint("openMcps") case OpenMcps
  @jsonHint("mcpServers") case McpServers(servers: List[McpServerView] = Nil)
  @jsonHint("clearTranscript") case ClearTranscript
  @jsonHint("transcript") case Transcript(turns: List[TurnView] = Nil)
  @jsonHint("rewindList") case RewindList(points: List[RewindPoint] = Nil)
  @jsonHint("rewound") case Rewound(promptIndex: Int)
  @jsonHint("childTranscript") case ChildTranscript(sessionId: SessionId, turns: List[TurnView] = Nil)
  @jsonHint("planView") case PlanView(markdown: String)
  @jsonHint("agents") case Agents(agents: List[AgentDef] = Nil, personas: List[PersonaDef] = Nil)
  @jsonHint("workflows") case Workflows(runs: List[WorkflowRun] = Nil)
  @jsonHint("dashboard") case Dashboard(rows: List[DashRow] = Nil)
  @jsonHint("btw") case Btw(text: String, done: Boolean = false)
  @jsonHint("doctor") case DoctorReport(findings: List[DoctorFinding] = Nil)
  @jsonHint("uiPrefs") case UiPrefs(theme: String = "vscode", compact: Boolean = false, vim: Boolean = false)
end HostMsg

object HostMsg:
  def settings(state: SettingsState): HostMsg =
    Settings(
      state.cliPath,
      state.nodePath,
      state.includeActiveFileByDefault,
      state.useCtrlEnterToSend,
      state.changesPresentation,
    )

  def permission(card: PermissionCard): HostMsg =
    Permission(card.requestId, card.toolCallId, card.title, card.options, card.hasDiff)

  def plan(card: PlanCard): HostMsg = Plan(card.requestId, card.planMarkdown)

  def question(card: QuestionCard): HostMsg = Question(card.requestId, card.questions)

  def elicit(card: ElicitCard): HostMsg =
    Elicit(card.requestId, card.serverName, card.mode, card.title, card.url)

  def changes(summary: ChangesSummary): HostMsg =
    Changes(summary.fileCount, summary.additions, summary.deletions, summary.files)

  def chip(p: PromptChip): HostMsg =
    ComposerChip(p.path, p.absPath, p.source, p.startLine, p.endLine)
end HostMsg

@jsonDiscriminator("_tag")
enum WebviewMsg derives JsonCodec:
  @jsonHint("ready") case Ready
  @jsonHint("send") case Send(text: String, images: List[ImageChip] = Nil)
  @jsonHint("queue") case Queue(text: String, images: List[ImageChip] = Nil)
  @jsonHint("queueSendNow") case QueueSendNow(id: QueueId)
  @jsonHint("queueDrop") case QueueDrop(id: QueueId)
  @jsonHint("stopTask") case StopTask(id: TaskId)
  @jsonHint("cancel") case Cancel
  @jsonHint("cancelTurn") case CancelTurnChoice(choice: String, keepChildren: Boolean = false)
  @jsonHint("slashPick") case SlashPick(name: String)
  @jsonHint("mentionQuery") case MentionQuery(query: String)
  @jsonHint("mentionPick") case MentionPick(path: String, absPath: String)
  @jsonHint("addSelection") case AddSelection
  @jsonHint("removeChip") case RemoveChip(
      absPath: String,
      startLine: Option[Int] = None,
      endLine: Option[Int] = None,
  )
  @jsonHint("cycleMode") case CycleMode
  @jsonHint("setMode") case SetMode(modeId: ModeId)
  @jsonHint("setModel") case SetModel(modelId: ModelId, effort: String = "")
  @jsonHint("setEffort") case SetEffort(level: String)
  @jsonHint("openSettings") case OpenSettings
  @jsonHint("setSetting") case SetSetting(key: String, value: String | Boolean)
  @jsonHint("permissionChoice") case PermissionChoice(requestId: RequestId, optionId: String)
  @jsonHint("permissionPark") case PermissionPark(requestId: RequestId)
  @jsonHint("openDiff") case OpenDiff(requestId: RequestId)
  @jsonHint("openFile") case OpenFile(path: String, line: Option[Int] = None)
  @jsonHint("planVerdict") case PlanVerdict(requestId: RequestId, verdict: PlanOutcome)
  @jsonHint("questionSubmit") case QuestionSubmit(requestId: RequestId, answers: List[QuestionAnswer])
  @jsonHint("questionDismiss") case QuestionDismiss(requestId: RequestId)
  @jsonHint("elicitAccept") case ElicitAccept(requestId: RequestId)
  @jsonHint("elicitDecline") case ElicitDecline(requestId: RequestId)
  @jsonHint("openChanges") case OpenChanges
  @jsonHint("keepChange") case KeepChange(path: String)
  @jsonHint("undoChange") case UndoChange(path: String)
  @jsonHint("keepTurn") case KeepTurn(turnId: TurnId)
  @jsonHint("undoTurn") case UndoTurn(turnId: TurnId)
  @jsonHint("keepAll") case KeepAll
  @jsonHint("undoAll") case UndoAll
  @jsonHint("closeDiff") case CloseDiff
  @jsonHint("newSession") case NewSession
  @jsonHint("resumeSession") case ResumeSession(
      sessionId: SessionId,
      restoreCode: Boolean = false,
      hasHistory: Boolean = false,
  )
  @jsonHint("workflowControl") case WorkflowControl(verb: String, name: String)
  @jsonHint("openSessionPicker") case OpenSessionPicker
  @jsonHint("closeSessionPicker") case CloseSessionPicker
  @jsonHint("renameSession") case RenameSession(sessionId: SessionId, title: String, auto: Boolean = false)
  @jsonHint("deleteSession") case DeleteSession(sessionId: SessionId)
  @jsonHint("copyOut") case CopyOut(
      text: String,
      path: Option[String] = None,
      backup: Boolean = false,
      conversation: Boolean = false,
  )
  @jsonHint("openRewind") case OpenRewind
  @jsonHint("closeRewind") case CloseRewind
  @jsonHint("rewindTo") case RewindTo(promptIndex: Int)
  @jsonHint("listMcps") case ListMcps
  @jsonHint("setMcpEnabled") case SetMcpEnabled(name: String, enabled: Boolean)
  @jsonHint("fork") case Fork(worktree: Boolean, directive: String = "")
  @jsonHint("log") case Log(message: String, level: String = "error")
  @jsonHint("attachChild") case AttachChild(id: TaskId)
  @jsonHint("detachChild") case DetachChild
  @jsonHint("steerChild") case SteerChild(id: TaskId, text: String, queue: Boolean = false)
  @jsonHint("viewPlan") case ViewPlan
  @jsonHint("openAgents") case OpenAgents
  @jsonHint("openDashboard") case OpenDashboard
  @jsonHint("openWorkflows") case OpenWorkflows
  @jsonHint("openDoctor") case OpenDoctor
  @jsonHint("openTheme") case OpenTheme
  @jsonHint("btw") case Btw(text: String)
  @jsonHint("setTheme") case SetTheme(id: String)
  @jsonHint("toggleCompact") case ToggleCompact
  @jsonHint("toggleVim") case ToggleVim
  @jsonHint("addImage") case AddImage(mime: String, data: String, name: String = "")
  @jsonHint("removeImage") case RemoveImage(id: String)
  @jsonHint("persistConfig") case PersistConfig(table: String, key: String, value: String)
end WebviewMsg
