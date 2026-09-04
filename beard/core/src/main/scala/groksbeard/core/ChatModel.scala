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
)

final case class TurnUser(text: String, chips: List[PromptChip] = Nil, steer: Boolean = false)

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
    queued: Int = 0,
    changes: Option[ChangesSummary] = None,
    diff: Option[DiffView] = None,
    error: Option[String] = None,
)

object ChatModel:
  val empty: ChatModel = ChatModel()

  def turnIsRunning(model: ChatModel): Boolean =
    model.turns.lastOption.exists(_.stopReason.isEmpty)

  def applyMsg(model: ChatModel, msg: HostMsg): ChatModel =
    msg match
      case HostMsg.Ready =>
        model
      case HostMsg.SessionMeta(sessionId, title, modeId, modes) =>
        model.copy(
          sessionId = if sessionId.nonEmpty then sessionId else model.sessionId,
          title = if title.nonEmpty then title else model.title,
          modeId = if modeId.nonEmpty then modeId else model.modeId,
          modes = if modes.nonEmpty then modes else model.modes,
        )
      case HostMsg.AvailableCommands(commands) =>
        model.copy(commands = commands)
      case HostMsg.MentionResults(query, files) =>
        model.copy(mentionQuery = query, mentionFiles = files)
      case HostMsg.Settings(cliPath, nodePath, include, ctrl, pres) =>
        model.copy(settings = SettingsState(cliPath, nodePath, include, ctrl, pres))
      case HostMsg.ComposerChip(path, absPath, source, startLine, endLine) =>
        model.copy(chips = PromptChip.upsert(model.chips, PromptChip(path, absPath, source, startLine, endLine)))
      case HostMsg.UserMessage(turnId, text, chips, steer) =>
        upsert(model.copy(chips = Nil), turnId)(_.copy(user = Some(TurnUser(text, chips, steer))))

      case HostMsg.AgentChunk(turnId, text, _) =>
        upsert(model, turnId)(t => t.copy(agent = t.agent + text))
      case HostMsg.ThoughtChunk(turnId, text) =>
        upsert(model, turnId)(t => t.copy(thought = t.thought + text))
      case HostMsg.ToolGroup(turnId, tools) =>
        upsert(model, turnId)(t => t.copy(tools = mergeTools(t.tools, tools)))
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
          model.copy(permission = None, plan = None, question = None, elicit = None, queued = 0),
          turnId,
        )(_.copy(stopReason = Some(reason)))
      case HostMsg.Queued(count) =>
        model.copy(queued = count)
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
          queued = 0,
          changes = None,
          diff = None,
          error = None,
        )

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
