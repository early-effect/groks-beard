package groksbeard.core

import zio.json.*

final case class ToolRow(
    id: String,
    title: String,
    kind: String,
    status: String,
    additions: Option[Int] = None,
    deletions: Option[Int] = None,
    input: Option[String] = None,
    output: Option[String] = None,
) derives JsonCodec

final case class TurnView(
    id: String,
    user: Option[TurnUser] = None,
    thought: String = "",
    agent: String = "",
    tools: List[ToolRow] = Nil,
    stopReason: Option[String] = None,
) derives JsonCodec

final case class TurnUser(text: String, chips: List[PromptChip] = Nil, steer: Boolean = false) derives JsonCodec

final case class QueuedPrompt(id: String, text: String, chips: List[PromptChip] = Nil) derives JsonCodec

object QueuedPrompt:
  def display(item: QueuedPrompt): String =
    val refs = item.chips.map(PromptChip.formatAtRef).filter(_.nonEmpty)
    (refs :+ item.text).filter(_.nonEmpty).mkString("\n")

final case class PermissionOption(optionId: String, name: String, kind: String) derives JsonCodec

final case class PermissionCard(
    requestId: String,
    toolCallId: String,
    title: String,
    options: List[PermissionOption],
    hasDiff: Boolean,
) derives JsonCodec

final case class PlanCard(requestId: String, planMarkdown: String) derives JsonCodec

final case class QuestionOption(id: String, label: String) derives JsonCodec

final case class AgentQuestion(
    id: String,
    prompt: String,
    options: List[QuestionOption],
    @jsonExclude allowMultiple: Boolean = false,
    @jsonExclude allowFreeText: Boolean = false,
) derives JsonCodec

final case class QuestionCard(requestId: String, questions: List[AgentQuestion]) derives JsonCodec

final case class ElicitCard(
    requestId: String,
    serverName: String,
    mode: String,
    title: String,
    url: Option[String] = None,
) derives JsonCodec

final case class ChangeFileView(
    path: String,
    kind: String,
    additions: Int,
    deletions: Int,
    wholeFile: Boolean = true,
    undoDisabled: Option[String] = None,
    turnId: String = "",
    turnTitle: String = "",
) derives JsonCodec

final case class ChangesSummary(
    fileCount: Int,
    additions: Int,
    deletions: Int,
    files: List[ChangeFileView] = Nil,
) derives JsonCodec

final case class DiffView(
    path: String,
    oldText: String,
    newText: String,
    wholeFile: Boolean = true,
)

final case class ChatModel(
    sessionId: String = "",
    title: String = "Grok's Beard",
    modeId: String = "normal",
    modes: List[ModeOption] = Nil,
    modelId: String = "",
    models: List[ModelOption] = Nil,
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
    sessions: List[SessionRow] = Nil,
    pickerOpen: Boolean = false,
    locked: Option[String] = None,
    queue: List[QueuedPrompt] = Nil,
    changes: Option[ChangesSummary] = None,
    diff: Option[DiffView] = None,
    error: Option[String] = None,
    runningSinceMs: Option[Long] = None,
    awaitingSession: Option[String] = None,
    inSession: Boolean = false,
    sessionOrder: Option[List[String]] = None,
)

object ChatModel:
  val empty: ChatModel = ChatModel()

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
        stopReason = t.stopReason.orElse(Some("end_turn")),
        tools = t.tools.map(r => r.copy(input = None, output = None)),
      )
    }

  def adopt(model: ChatModel, sessionId: String, title: String): ChatModel =
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
      error = None,
      pickerOpen = false,
      locked = None,
      runningSinceMs = None,
      awaitingSession = Some(sessionId),
      inSession = sessionId.nonEmpty,
      sessionOrder = order,
    )
  end adopt

  def dropHost(waiting: Option[String], msg: HostMsg): Boolean =
    waiting match
      case None       => false
      case Some(want) =>
        msg match
          case HostMsg.Ready | HostMsg.ClearTranscript | _: HostMsg.Transcript | _: HostMsg.Error |
              _: HostMsg.AvailableCommands | _: HostMsg.Settings | _: HostMsg.MentionResults | _: HostMsg.SessionList =>
            false
          case m: HostMsg.SessionMeta =>
            want.nonEmpty && m.sessionId.nonEmpty && m.sessionId != want
          case m: HostMsg.SessionLocked =>
            want.nonEmpty && m.sessionId.nonEmpty && m.sessionId != want
          case _ => true

  def catchesUp(waiting: Option[String], msg: HostMsg): Boolean =
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
      case HostMsg.SessionMeta(sessionId, title, modeId, modes, occupancy, modelId, models) =>
        model.copy(
          sessionId = if sessionId.nonEmpty then sessionId else model.sessionId,
          title = if title.nonEmpty then title else model.title,
          modeId = if modeId.nonEmpty then modeId else model.modeId,
          modes = if modes.nonEmpty then modes else model.modes,
          occupancy = occupancy.orElse(model.occupancy),
          modelId = if modelId.nonEmpty then modelId else model.modelId,
          models = if models.nonEmpty then models else model.models,
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
      case HostMsg.ToolGroup(turnId, tools) =>
        markRunning(upsert(model, turnId)(t => t.copy(tools = mergeTools(t.tools, tools))), nowMs)
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
        model.copy(error = Some(message))
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
          error = None,
          pickerOpen = false,
          locked = None,
          runningSinceMs = None,
        )

  private def markRunning(model: ChatModel, nowMs: Long): ChatModel =
    if ChatModel.turnIsRunning(model) then model.copy(runningSinceMs = model.runningSinceMs.orElse(Some(nowMs)))
    else model.copy(runningSinceMs = None)

  private def upsert(model: ChatModel, turnId: String)(patch: TurnView => TurnView): ChatModel =
    val idx = model.turns.indexWhere(_.id == turnId)
    if idx < 0 then model.copy(turns = model.turns :+ patch(TurnView(turnId)))
    else
      val next = model.turns.updated(idx, patch(model.turns(idx)))
      model.copy(turns = next)

  private def mergeTools(existing: List[ToolRow], incoming: List[ToolRow]): List[ToolRow] =
    incoming.foldLeft(existing) { (acc, row) =>
      val idx = acc.indexWhere(_.id == row.id)
      if idx < 0 then
        acc :+ row.copy(
          title = if row.title.nonEmpty then row.title else "Tool",
          kind = if row.kind.nonEmpty then row.kind else "other",
        )
      else
        val prev = acc(idx)
        acc.updated(
          idx,
          prev.copy(
            title = if row.title.nonEmpty then row.title else prev.title,
            kind = if row.kind.nonEmpty then row.kind else prev.kind,
            status = if row.status.nonEmpty then row.status else prev.status,
            additions = row.additions.orElse(prev.additions),
            deletions = row.deletions.orElse(prev.deletions),
            input = row.input.filter(_.nonEmpty).orElse(prev.input),
            output = row.output.filter(_.nonEmpty).orElse(prev.output),
          ),
        )
      end if
    }
end ChatModel
