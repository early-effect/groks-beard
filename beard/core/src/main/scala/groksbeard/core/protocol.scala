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
      sessionId: String,
      title: String,
      modeId: String,
      availableModes: List[ModeOption] = Nil,
      occupancy: Option[Occupancy] = None,
      modelId: String = "",
      availableModels: List[ModelOption] = Nil,
  )
  @jsonHint("sessionList") case SessionList(
      sessions: List[SessionRow],
      currentId: String = "",
      openPicker: Boolean = false,
  )
  @jsonHint("sessionLocked") case SessionLocked(sessionId: String, message: String)
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
      source: String,
      startLine: Option[Int] = None,
      endLine: Option[Int] = None,
  )
  @jsonHint("userMessage") case UserMessage(
      turnId: String,
      text: String,
      chips: List[PromptChip] = Nil,
      steer: Boolean = false,
  )
  @jsonHint("agentChunk") case AgentChunk(turnId: String, text: String, messageId: Option[String] = None)
  @jsonHint("thoughtChunk") case ThoughtChunk(turnId: String, text: String)
  @jsonHint("toolGroup") case ToolGroup(turnId: String, tools: List[ToolRow])
  @jsonHint("permissionCard") case Permission(
      requestId: String,
      toolCallId: String,
      title: String,
      options: List[PermissionOption] = Nil,
      hasDiff: Boolean = false,
  )
  @jsonHint("planCard") case Plan(requestId: String, planMarkdown: String)
  @jsonHint("questionCard") case Question(requestId: String, questions: List[AgentQuestion])
  @jsonHint("elicitCard") case Elicit(
      requestId: String,
      serverName: String,
      mode: String,
      title: String,
      url: Option[String] = None,
  )
  @jsonHint("turnEnd") case TurnEnd(turnId: String, stopReason: String)
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
  @jsonHint("clearTranscript") case ClearTranscript
  @jsonHint("transcript") case Transcript(turns: List[TurnView] = Nil)
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
  @jsonHint("send") case Send(text: String)
  @jsonHint("queue") case Queue(text: String)
  @jsonHint("cancel") case Cancel
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
  @jsonHint("setMode") case SetMode(modeId: String)
  @jsonHint("setModel") case SetModel(modelId: String)
  @jsonHint("openSettings") case OpenSettings
  @jsonHint("setSetting") case SetSetting(key: String, value: String | Boolean)
  @jsonHint("permissionChoice") case PermissionChoice(requestId: String, optionId: String)
  @jsonHint("permissionPark") case PermissionPark(requestId: String)
  @jsonHint("openDiff") case OpenDiff(requestId: String)
  @jsonHint("planVerdict") case PlanVerdict(requestId: String, verdict: String)
  @jsonHint("questionChoice") case QuestionChoice(requestId: String, questionId: String, optionId: String)
  @jsonHint("questionDismiss") case QuestionDismiss(requestId: String)
  @jsonHint("elicitAccept") case ElicitAccept(requestId: String)
  @jsonHint("elicitDecline") case ElicitDecline(requestId: String)
  @jsonHint("openChanges") case OpenChanges
  @jsonHint("keepChange") case KeepChange(path: String)
  @jsonHint("undoChange") case UndoChange(path: String)
  @jsonHint("closeDiff") case CloseDiff
  @jsonHint("newSession") case NewSession
  @jsonHint("resumeSession") case ResumeSession(sessionId: String)
  @jsonHint("openSessionPicker") case OpenSessionPicker
  @jsonHint("closeSessionPicker") case CloseSessionPicker
end WebviewMsg
