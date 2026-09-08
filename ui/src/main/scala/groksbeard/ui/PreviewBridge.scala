package groksbeard.ui

import groksbeard.core.*

final class PreviewBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  private var settings: SettingsState   = SettingsState.defaults
  private var modeId: ModeId            = ModeId.Normal
  private var modelId: ModelId          = ModelId("grok-4.6")
  private var effort: String            = "high"

  private val modes = List(
    ModeOption(ModeId.Normal, "Normal"),
    ModeOption(ModeId.Plan, "Plan"),
    ModeOption(ModeId.Auto, "Auto"),
    ModeOption(ModeId.AlwaysApprove, "Always approve"),
  )

  private val models = List(
    ModelOption(ModelId("grok-4.6"), "Grok 4.6", _meta = Some(Effort.grokMeta())),
    ModelOption(ModelId("grok-code-fast-1"), "Grok Code Fast"),
  )

  private val commands = SessionCommands.merge(
    List(
      SlashCommand("compact", "Compact context"),
      SlashCommand("always-approve", "Skip permission prompts"),
      SlashCommand("init", "Initialize project memory"),
    )
  )

  private var sessions = List(
    SessionRow(SessionId("preview"), "New session", activityMs = 20),
    SessionRow(
      SessionId("disk-1"),
      "Effect-TS Grok Build VS Code Plugin Plan",
      activityMs = 10,
      lastTurn = Some("Continue the plan"),
    ),
    SessionRow(SessionId("disk-2"), "Ascent chat chrome", activityMs = 5, summary = Some("Composer and cards")),
  )

  private val files = List(
    MentionFile("src/Main.scala", "/repo/src/Main.scala"),
    MentionFile("src/Foo.scala", "/repo/src/Foo.scala"),
    MentionFile("README.md", "/repo/README.md"),
  )

  private var pending: List[ChangeFileView] =
    List(
      ChangeFileView(
        PreviewDiffs.MainPath,
        ChangeKind.Modify,
        2,
        1,
        wholeFile = true,
        turnId = TurnId("t3"),
        turnTitle = "Patch Main.scala",
      )
    )
  private var currentId  = SessionId.empty
  private var pickerOpen = false
  private var mcps       = PreviewScenes.mcps

  def post(msg: WebviewMsg): Unit =
    msg match
      case WebviewMsg.Ready =>
        currentId = SessionId.empty
        pickerOpen = false
        emit(HostMsg.Ready)
        emitMeta(SessionId.empty, "Grok's Beard")
        emit(HostMsg.AvailableCommands(commands))
        emit(HostMsg.settings(settings))
        emit(HostMsg.SessionList(sessions, SessionId.empty, openPicker = false))
        emit(HostMsg.McpServers(mcps))
      case WebviewMsg.MentionQuery(query) =>
        val q    = query.toLowerCase
        val hits =
          if query.isEmpty then files
          else files.filter(f => f.path.toLowerCase.contains(q))
        emit(HostMsg.MentionResults(query, hits))
      case WebviewMsg.SetMode(id) =>
        modeId = id
        emitMeta()
      case WebviewMsg.SetModel(id, requested) =>
        modelId = id
        effort = resolveEffort(requested)
        emitMeta()
      case WebviewMsg.SetEffort(level) =>
        val allowed = Effort.of(models.find(_.modelId == modelId))
        Effort.pick(level, allowed) match
          case Some(e) =>
            effort = e.value
            emitMeta()
          case None =>
            emit(HostMsg.Error(Effort.unknown(level, allowed)))
      case WebviewMsg.CycleMode =>
        val ids  = modes.map(_.id)
        val next = ids.lift(ids.indexOf(modeId) + 1).getOrElse(ids.head)
        modeId = next
        emitMeta()
      case WebviewMsg.OpenSettings =>
        emit(HostMsg.settings(settings))
      case WebviewMsg.SetSetting(key, value) =>
        settings = key match
          case "useCtrlEnterToSend" =>
            value match
              case b: Boolean => settings.copy(useCtrlEnterToSend = b)
              case _          => settings
          case "includeActiveFileByDefault" =>
            value match
              case b: Boolean => settings.copy(includeActiveFileByDefault = b)
              case _          => settings
          case "changesPresentation" =>
            value match
              case s: String => settings.copy(changesPresentation = s)
              case _         => settings
          case "cliPath" =>
            value match
              case s: String => settings.copy(cliPath = s)
              case _         => settings
          case "nodePath" =>
            value match
              case s: String => settings.copy(nodePath = s)
              case _         => settings
          case _ => settings
        emit(HostMsg.settings(settings))
      case WebviewMsg.Send(text) =>
        emit(HostMsg.UserMessage(TurnId("preview-turn"), text))
        emit(HostMsg.AgentChunk(TurnId("preview-turn"), s"Echo: **$text**"))
        emit(
          HostMsg.ToolCall(
            TurnId("preview-turn"),
            ToolRow(
              ToolCallId("call_1"),
              "Edit Main.scala",
              ToolKind.Edit,
              ToolStatus.Completed,
              additions = Some(2),
              deletions = Some(1),
              input = Some(PreviewDiffs.MainPath),
            ),
          )
        )
        emitChanges()
        emit(HostMsg.TurnEnd(TurnId("preview-turn"), StopReason.EndTurn))
      case WebviewMsg.Queue(text) =>
        emit(HostMsg.Queued(List(QueuedPrompt(QueueId("preview-q"), text))))
      case WebviewMsg.QueueSendNow(_) | WebviewMsg.QueueDrop(_) | WebviewMsg.StopTask(_) =>
        ()
      case WebviewMsg.PermissionChoice(_, _) | WebviewMsg.PlanVerdict(_, _) | WebviewMsg.QuestionSubmit(_, _) |
          WebviewMsg.QuestionDismiss(_) | WebviewMsg.ElicitAccept(_) | WebviewMsg.ElicitDecline(_) |
          WebviewMsg.Cancel =>
        emit(HostMsg.TurnEnd(TurnId("t2"), StopReason.EndTurn))
      case WebviewMsg.MentionPick(path, absPath) =>
        emit(HostMsg.chip(PromptChip(path, absPath, source = ChipSource.Mention)))
      case WebviewMsg.SlashPick(name) =>
        if SessionCommands.isNew(name) then post(WebviewMsg.NewSession)
        else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then post(WebviewMsg.OpenSessionPicker)
        else if SessionCommands.isRewind(name) then post(WebviewMsg.OpenRewind)
        else if SessionCommands.isMcps(name) then post(WebviewMsg.ListMcps)
      case WebviewMsg.ListMcps =>
        emit(HostMsg.McpServers(mcps))
      case WebviewMsg.SetMcpEnabled(name, enabled) =>
        mcps = mcps.map { row =>
          if row.name == name then row.copy(enabled = enabled) else row
        }
        emit(HostMsg.McpServers(mcps))
      case WebviewMsg.OpenRewind | WebviewMsg.CloseRewind =>
        ()
      case WebviewMsg.RewindTo(index) =>
        emit(HostMsg.Rewound(index))
      case WebviewMsg.NewSession =>
        currentId = SessionId.empty
        pickerOpen = false
        emit(HostMsg.ClearTranscript)
        emitMeta(SessionId.empty, "Grok's Beard")
        emit(HostMsg.SessionList(sessions, SessionId.empty, openPicker = false))
      case WebviewMsg.ResumeSession(id) =>
        currentId = id
        pickerOpen = false
        val title = sessions.find(_.id == id).map(_.title).getOrElse(id.value)
        emit(HostMsg.ClearTranscript)
        emitMeta(id, title)
        emit(
          HostMsg.Transcript(
            List(
              TurnView(
                TurnId("resume-turn"),
                user = Some(TurnUser("hello from disk")),
                agent = s"Resumed **$title**.",
                stopReason = Some(StopReason.EndTurn),
              )
            )
          )
        )
        emit(HostMsg.SessionList(sessions, id, openPicker = false))
      case WebviewMsg.OpenSessionPicker =>
        pickerOpen = true
        emit(HostMsg.SessionList(sessions, currentId, openPicker = true))
      case WebviewMsg.CloseSessionPicker =>
        pickerOpen = false
        emit(HostMsg.SessionList(sessions, currentId, openPicker = false))
      case WebviewMsg.RenameSession(id, title, auto) =>
        val target = if id.nonEmpty then id else currentId
        sessions = sessions.map { row =>
          if row.id != target then row
          else if auto then row
          else row.copy(title = title)
        }
        val shown = sessions.find(_.id == target).map(_.title).getOrElse(title)
        if target == currentId then emitMeta(target, shown)
        emit(HostMsg.SessionList(sessions, currentId, openPicker = pickerOpen))
      case WebviewMsg.DeleteSession(id) =>
        val target = if id.nonEmpty then id else currentId
        sessions = sessions.filterNot(_.id == target)
        if target == currentId then
          currentId = SessionId.empty
          pickerOpen = false
          emit(HostMsg.ClearTranscript)
          emitMeta(SessionId.empty, "Grok's Beard")
          emit(HostMsg.SessionList(sessions, SessionId.empty, openPicker = false))
        else emit(HostMsg.SessionList(sessions, currentId, openPicker = pickerOpen))
      case WebviewMsg.PermissionPark(_) | WebviewMsg.AddSelection | WebviewMsg.RemoveChip(_, _, _) =>
        ()
      case WebviewMsg.OpenDiff(_) | WebviewMsg.OpenChanges =>
        emit(
          HostMsg.DiffPreview(PreviewDiffs.MainPath, PreviewDiffs.MainOld, PreviewDiffs.MainNew, wholeFile = true)
        )
      case WebviewMsg.KeepChange(path) =>
        pending = pending.filterNot(_.path == path)
        emitChanges()
        emit(HostMsg.ClearDiff)
      case WebviewMsg.UndoChange(path) =>
        pending = pending.filterNot(_.path == path)
        emitChanges()
        emit(HostMsg.ClearDiff)
      case WebviewMsg.KeepTurn(turnId) =>
        pending = pending.filterNot(_.turnId == turnId)
        emitChanges()
        emit(HostMsg.ClearDiff)
      case WebviewMsg.UndoTurn(turnId) =>
        pending = pending.filterNot(_.turnId == turnId)
        emitChanges()
        emit(HostMsg.ClearDiff)
      case WebviewMsg.KeepAll | WebviewMsg.UndoAll =>
        pending = Nil
        emitChanges()
        emit(HostMsg.ClearDiff)
      case WebviewMsg.CloseDiff =>
        emit(HostMsg.ClearDiff)
      case WebviewMsg.CopyOut(_, path, _, conversation) =>
        emit(HostMsg.Copied(TranscriptCopy.toast(path, conversation)))
      case WebviewMsg.Log(message, _) =>
        emit(HostMsg.Error(message, Some(Wire.Decode)))

  def onHost(f: HostMsg => Unit): Unit =
    listener = f

  private def resolveEffort(requested: String): String =
    val target  = models.find(_.modelId == modelId)
    val allowed = Effort.of(target)
    if requested.nonEmpty then Effort.pick(requested, allowed).map(_.value).getOrElse(Effort.defaultOf(target))
    else if allowed.exists(_.value == effort) then effort
    else Effort.defaultOf(target)

  private def emit(msg: HostMsg): Unit =
    listener(msg)

  private def emitMeta(sessionId: SessionId = currentId, title: String = "Grok's Beard"): Unit =
    emit(
      HostMsg.SessionMeta(sessionId, title, modeId, modes, modelId = modelId, availableModels = models, effort = effort)
    )

  private def emitChanges(): Unit =
    val (add, del) = pending.foldLeft((0, 0)) { case ((a, d), f) => (a + f.additions, d + f.deletions) }
    emit(HostMsg.changes(ChangesSummary(pending.size, add, del, pending)))
end PreviewBridge

object PreviewBridge:
  def hasSceneQuery: Boolean =
    BeardPath.sceneName(ascent.Location.parse(ascent.dom.window.location.search)).isDefined

  def sceneFromLocation: String =
    BeardPath.sceneName(ascent.Location.parse(ascent.dom.window.location.search)).getOrElse("empty")
end PreviewBridge
