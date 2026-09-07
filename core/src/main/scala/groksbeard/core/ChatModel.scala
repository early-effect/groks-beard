package groksbeard.core

import ascent.squawk.Eq
import zio.json.*

final case class ToolRow(
    id: ToolCallId,
    title: String,
    kind: ToolKind,
    status: ToolStatus,
    additions: Option[Int] = None,
    deletions: Option[Int] = None,
    input: Option[String] = None,
    output: Option[String] = None,
) derives JsonCodec

object ToolRow:
  given Eq[ToolRow] = (a, b) => a == b

final case class TurnView(
    id: TurnId,
    user: Option[TurnUser] = None,
    thought: String = "",
    agent: String = "",
    tools: List[ToolRow] = Nil,
    stopReason: Option[StopReason] = None,
) derives JsonCodec,
      Eq

final case class TurnUser(text: String, chips: List[PromptChip] = Nil, steer: Boolean = false) derives JsonCodec, Eq

final case class QueuedPrompt(id: QueueId, text: String, chips: List[PromptChip] = Nil) derives JsonCodec, Eq

object QueuedPrompt:
  def display(item: QueuedPrompt): String =
    val refs = item.chips.map(PromptChip.formatAtRef).filter(_.nonEmpty)
    (refs :+ item.text).filter(_.nonEmpty).mkString("\n")

final case class PermissionOption(optionId: String, name: String, kind: PermissionKind) derives JsonCodec, Eq

final case class PermissionCard(
    requestId: RequestId,
    toolCallId: ToolCallId,
    title: String,
    options: List[PermissionOption],
    hasDiff: Boolean,
) derives JsonCodec,
      Eq

final case class PlanCard(requestId: RequestId, planMarkdown: String) derives JsonCodec, Eq

final case class QuestionOption(id: String, label: String) derives JsonCodec, Eq

final case class AgentQuestion(
    id: String,
    prompt: String,
    options: List[QuestionOption],
    allowMultiple: Boolean = false,
    allowFreeText: Boolean = false,
) derives JsonCodec,
      Eq

final case class QuestionCard(requestId: RequestId, questions: List[AgentQuestion]) derives JsonCodec, Eq

final case class ElicitCard(
    requestId: RequestId,
    serverName: String,
    mode: ElicitMode,
    title: String,
    url: Option[String] = None,
) derives JsonCodec,
      Eq

final case class ChangeFileView(
    path: String,
    kind: ChangeKind,
    additions: Int,
    deletions: Int,
    wholeFile: Boolean = true,
    undoDisabled: Option[String] = None,
    turnId: TurnId = TurnId.empty,
    turnTitle: String = "",
) derives JsonCodec,
      Eq

final case class ChangesSummary(
    fileCount: Int,
    additions: Int,
    deletions: Int,
    files: List[ChangeFileView] = Nil,
) derives JsonCodec,
      Eq

final case class DiffView(
    path: String,
    oldText: String,
    newText: String,
    wholeFile: Boolean = true,
) derives Eq

final case class ChatModel(
    sessionId: SessionId = SessionId.empty,
    title: String = "Grok's Beard",
    modeId: ModeId = ModeId.Normal,
    modes: List[ModeOption] = Nil,
    modelId: ModelId = ModelId.empty,
    models: List[ModelOption] = Nil,
    effort: String = "",
    commands: List[SlashCommand] = Nil,
    mentionQuery: String = "",
    mentionFiles: List[MentionFile] = Nil,
    chips: List[PromptChip] = Nil,
    settings: SettingsState = SettingsState.defaults,
    turns: List[TurnView] = Nil,
    permission: Option[PermissionCard] = None,
    plan: Option[PlanCard] = None,
    question: Option[QuestionCard] = None,
    elicit: Option[ElicitCard] = None,
    occupancy: Option[Occupancy] = None,
    cwd: String = "",
    sessions: List[SessionRow] = Nil,
    pickerOpen: Boolean = false,
    locked: Option[String] = None,
    queue: List[QueuedPrompt] = Nil,
    changes: Option[ChangesSummary] = None,
    diff: Option[DiffView] = None,
    todos: List[TodoEntry] = Nil,
    error: Option[String] = None,
    runningSinceMs: Option[Long] = None,
    awaitingSession: Option[SessionId] = None,
    inSession: Boolean = false,
    sessionOrder: Option[List[SessionId]] = None,
    rewind: List[RewindPoint] = Nil,
    rewindConfirm: Option[RewindPoint] = None,
    mcps: List[McpServerView] = Nil,
)

object ChatModel:
  val empty: ChatModel = ChatModel()

  // Always copied on update. Derived Eq inlines past 32 fields and walks the transcript.
  given Eq[ChatModel] = Eq.byRef

  def turnIsRunning(model: ChatModel): Boolean =
    model.turns.lastOption.exists(_.stopReason.isEmpty)

  def isLoading(model: ChatModel): Boolean =
    model.awaitingSession.exists(_.nonEmpty) && model.turns.isEmpty

  def isHome(model: ChatModel): Boolean =
    !model.inSession && !isLoading(model) && model.turns.isEmpty

  def isEmptySession(model: ChatModel): Boolean =
    model.inSession && !isLoading(model) && model.turns.isEmpty

  def thaw(model: ChatModel): ChatModel =
    model.copy(sessionOrder = None)

  def listed(model: ChatModel): List[SessionRow] =
    val rows =
      if model.sessionOrder.isDefined || model.pickerOpen then model.sessions
      else model.sessions.filterNot(r => r.id == model.sessionId && model.turns.isEmpty)
    SessionIndex.present(rows, model.sessionOrder)

  def snapshotTurns(turns: List[TurnView]): List[TurnView] =
    turns.map { t =>
      t.copy(
        thought = "",
        stopReason = t.stopReason.orElse(Some(StopReason.EndTurn)),
        tools = t.tools.map(r => r.copy(input = None, output = None)),
      )
    }

  def adopt(model: ChatModel, sessionId: SessionId, title: String): ChatModel =
    val order = if sessionId.nonEmpty then Some(listed(model).map(_.id)) else None
    model.copy(
      sessionId = sessionId,
      title = if title.nonEmpty then title else model.title,
      turns = Nil,
      chips = Nil,
      permission = None,
      plan = None,
      question = None,
      elicit = None,
      queue = Nil,
      changes = None,
      diff = None,
      todos = Nil,
      error = None,
      pickerOpen = false,
      locked = None,
      runningSinceMs = None,
      awaitingSession = Some(sessionId),
      inSession = sessionId.nonEmpty,
      sessionOrder = order,
      rewind = Nil,
      rewindConfirm = None,
    )
  end adopt

  def dropHost(waiting: Option[SessionId], msg: HostMsg): Boolean =
    waiting match
      case None       => false
      case Some(want) =>
        msg match
          case HostMsg.Ready | HostMsg.ClearTranscript | HostMsg.ToggleTodos | HostMsg.ToggleQueue |
              HostMsg.OpenPalette | HostMsg.OpenMcps | _: HostMsg.McpServers | _: HostMsg.Transcript |
              _: HostMsg.Error | _: HostMsg.Copied | _: HostMsg.AvailableCommands | _: HostMsg.Settings |
              _: HostMsg.MentionResults | _: HostMsg.SessionList | _: HostMsg.Elicit | _: HostMsg.Permission |
              _: HostMsg.Plan | _: HostMsg.Question =>
            false
          case m: HostMsg.SessionMeta =>
            want.nonEmpty && m.sessionId.nonEmpty && m.sessionId != want
          case m: HostMsg.SessionLocked =>
            want.nonEmpty && m.sessionId.nonEmpty && m.sessionId != want
          case _ => true

  def catchesUp(waiting: Option[SessionId], msg: HostMsg): Boolean =
    msg match
      case _: HostMsg.Transcript    => waiting.nonEmpty
      case m: HostMsg.SessionLocked =>
        m.sessionId.nonEmpty && waiting.exists(w => w.isEmpty || w == m.sessionId)
      case _ => false

  def applyMsg(model: ChatModel, msg: HostMsg): ChatModel =
    applyMsg(model, msg, 0L)

  def applyMsg(model: ChatModel, msg: HostMsg, nowMs: Long): ChatModel =
    if dropHost(model.awaitingSession, msg) then model
    else
      val next = foldMsg(model, msg, nowMs)
      if catchesUp(model.awaitingSession, msg) then next.copy(awaitingSession = None)
      else next

  private def foldMsg(model: ChatModel, msg: HostMsg, nowMs: Long): ChatModel =
    msg match
      case HostMsg.Ready =>
        model
      case HostMsg.SessionMeta(sessionId, title, modeId, modes, occupancy, modelId, models, effort, cwd) =>
        model.copy(
          sessionId = if sessionId.nonEmpty then sessionId else model.sessionId,
          title = if title.nonEmpty then title else model.title,
          modeId = if modeId.nonEmpty then modeId else model.modeId,
          modes = if modes.nonEmpty then modes else model.modes,
          occupancy = occupancy.orElse(model.occupancy),
          modelId = if modelId.nonEmpty then modelId else model.modelId,
          models = if models.nonEmpty then models else model.models,
          effort = if modelId.nonEmpty then effort else if effort.nonEmpty then effort else model.effort,
          cwd = if cwd.nonEmpty then cwd else model.cwd,
        )
      case HostMsg.SessionList(sessions, currentId, openPicker) =>
        val keepCurrent =
          model.awaitingSession.exists(want => want.nonEmpty && currentId.nonEmpty && want != currentId)
        model.copy(
          sessions = sessions,
          sessionId = if currentId.nonEmpty && !keepCurrent then currentId else model.sessionId,
          pickerOpen = openPicker,
          locked = if openPicker then model.locked else None,
          sessionOrder = if openPicker then None else model.sessionOrder,
        )
      case HostMsg.SessionLocked(_, message) =>
        model.copy(locked = Some(message), pickerOpen = true, awaitingSession = None)
      case HostMsg.AvailableCommands(commands) =>
        model.copy(commands = commands)
      case HostMsg.MentionResults(query, files) =>
        model.copy(mentionQuery = query, mentionFiles = files)
      case HostMsg.Settings(cliPath, nodePath, include, ctrl, pres) =>
        model.copy(settings = SettingsState(cliPath, nodePath, include, ctrl, pres))
      case HostMsg.Transcript(turns) =>
        model.copy(
          turns = turns,
          awaitingSession = None,
          runningSinceMs = None,
          pickerOpen = false,
          inSession = true,
        )
      case HostMsg.ComposerChip(path, absPath, source, startLine, endLine) =>
        model.copy(chips = PromptChip.upsert(model.chips, PromptChip(path, absPath, source, startLine, endLine)))
      case HostMsg.UserMessage(turnId, text, chips, steer) =>
        markRunning(
          upsert(model.copy(chips = Nil, inSession = true), turnId)(_.copy(user = Some(TurnUser(text, chips, steer)))),
          nowMs,
        )

      case HostMsg.AgentChunk(turnId, text, _) =>
        markRunning(upsert(model, turnId)(t => t.copy(agent = t.agent + text)), nowMs)
      case HostMsg.ThoughtChunk(turnId, text) =>
        markRunning(upsert(model, turnId)(t => t.copy(thought = t.thought + text)), nowMs)
      case HostMsg.ToolCall(turnId, row) =>
        markRunning(upsert(model, turnId)(t => t.copy(tools = mergeTool(t.tools, row))), nowMs)
      case HostMsg.ToolChunk(turnId, id, text, snapshot) =>
        markRunning(upsert(model, turnId)(t => t.copy(tools = pullToolOutput(t.tools, id, text, snapshot))), nowMs)
      case HostMsg.Permission(requestId, toolCallId, title, options, hasDiff) =>
        model.copy(permission = Some(PermissionCard(requestId, toolCallId, title, options, hasDiff)))
      case HostMsg.Plan(requestId, markdown) =>
        model.copy(plan = Some(PlanCard(requestId, markdown)))
      case HostMsg.Question(requestId, questions) =>
        model.copy(question = Some(QuestionCard(requestId, questions)))
      case HostMsg.Elicit(requestId, serverName, mode, title, url) =>
        model.copy(elicit = Some(ElicitCard(requestId, serverName, mode, title, url)))
      case HostMsg.TurnEnd(turnId, reason) =>
        upsert(
          model.copy(permission = None, plan = None, question = None, elicit = None, runningSinceMs = None),
          turnId,
        )(_.copy(stopReason = Some(reason)))
      case HostMsg.Queued(items) =>
        model.copy(queue = items)
      case HostMsg.Changes(fileCount, additions, deletions, files) =>
        val summary = ChangesSummary(fileCount, additions, deletions, files)
        model.copy(changes = if fileCount > 0 then Some(summary) else None)
      case HostMsg.DiffPreview(path, oldText, newText, wholeFile) =>
        model.copy(diff = Some(DiffView(path, oldText, newText, wholeFile)))
      case HostMsg.ClearDiff =>
        model.copy(diff = None)
      case HostMsg.Error(message, _) =>
        model.copy(error = Option(message).filter(_.nonEmpty))
      case HostMsg.Copied(message, _) =>
        model.copy(error = Some(message))
      case HostMsg.Todos(entries) =>
        model.copy(todos = Todos.fromEntries(entries))
      case HostMsg.ToggleTodos | HostMsg.ToggleQueue | HostMsg.OpenPalette | HostMsg.OpenMcps =>
        model
      case HostMsg.McpServers(servers) =>
        model.copy(mcps = servers)
      case HostMsg.ClearTranscript =>
        model.copy(
          turns = Nil,
          chips = Nil,
          permission = None,
          plan = None,
          question = None,
          elicit = None,
          queue = Nil,
          changes = None,
          diff = None,
          todos = Nil,
          error = None,
          pickerOpen = false,
          locked = None,
          runningSinceMs = None,
          rewind = Nil,
          rewindConfirm = None,
        )
      case HostMsg.RewindList(points) =>
        val confirm =
          if points.isEmpty then None
          else model.rewindConfirm.flatMap(p => points.find(_.promptIndex == p.promptIndex))
        model.copy(rewind = points, rewindConfirm = confirm, pickerOpen = false, error = None)
      case HostMsg.Rewound(promptIndex) =>
        model.copy(
          turns = Rewind.truncate(model.turns, promptIndex),
          rewind = Nil,
          rewindConfirm = None,
          queue = Nil,
          chips = Nil,
          permission = None,
          plan = None,
          question = None,
          elicit = None,
          changes = None,
          diff = None,
          todos = Nil,
          runningSinceMs = None,
          error = None,
        )

  private def markRunning(model: ChatModel, nowMs: Long): ChatModel =
    if ChatModel.turnIsRunning(model) then model.copy(runningSinceMs = model.runningSinceMs.orElse(Some(nowMs)))
    else model.copy(runningSinceMs = None)

  private def upsert(model: ChatModel, turnId: TurnId)(patch: TurnView => TurnView): ChatModel =
    val idx = model.turns.indexWhere(_.id == turnId)
    if idx < 0 then model.copy(turns = model.turns :+ patch(TurnView(turnId)))
    else
      val next = model.turns.updated(idx, patch(model.turns(idx)))
      model.copy(turns = next)

  private def mergeTool(existing: List[ToolRow], row: ToolRow): List[ToolRow] =
    val idx = existing.indexWhere(_.id == row.id)
    if idx < 0 then
      existing :+ row.copy(
        title = if row.title.nonEmpty then row.title else "Tool",
        output = None,
      )
    else
      val prev = existing(idx)
      existing.updated(
        idx,
        prev.copy(
          title = mergeToolTitle(prev, row),
          kind = mergeToolKind(prev, row),
          status = row.status,
          additions = row.additions.orElse(prev.additions),
          deletions = row.deletions.orElse(prev.deletions),
          input = row.input.filter(_.nonEmpty).orElse(prev.input),
        ),
      )
    end if
  end mergeTool

  private def pullToolOutput(
      existing: List[ToolRow],
      id: ToolCallId,
      text: String,
      snapshot: Boolean,
  ): List[ToolRow] =
    val idx = existing.indexWhere(_.id == id)
    if idx < 0 then
      val out = ToolOutput.pull("", text, snapshot)
      existing :+ ToolRow(
        id,
        "run_terminal_command",
        ToolKind.Execute,
        ToolStatus.InProgress,
        output = Some(out).filter(_.nonEmpty),
      )
    else
      val prev = existing(idx)
      val out  = ToolOutput.pull(prev.output.getOrElse(""), text, snapshot)
      existing.updated(idx, prev.copy(output = Some(out).filter(_.nonEmpty)))
    end if
  end pullToolOutput

  private def mergeToolTitle(prev: ToolRow, row: ToolRow): String =
    val incoming = row.title.trim
    val had      = prev.title.trim
    if had.nonEmpty && (incoming.isEmpty || incoming == "Tool" || incoming.startsWith("Execute `")) then had
    else if incoming.nonEmpty then incoming
    else if had.nonEmpty then had
    else "Tool"

  private def mergeToolKind(prev: ToolRow, row: ToolRow): ToolKind =
    if row.kind == ToolKind.Other && prev.kind != ToolKind.Other then prev.kind else row.kind
end ChatModel
