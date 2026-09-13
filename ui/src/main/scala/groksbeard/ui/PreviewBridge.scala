package groksbeard.ui

import groksbeard.core.*

final class PreviewBridge extends HostBridge:
  var sent: List[WebviewMsg]            = Nil
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
      SlashCommand("workflow", "Launch or manage a workflow"),
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
  private var uiTheme    = "vscode"
  private var uiCompact  = false
  private var uiVim      = false

  def post(msg: WebviewMsg): Unit =
    sent = sent :+ msg
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
        settings = key.patch(settings, value)
        emit(HostMsg.settings(settings))
      case WebviewMsg.Fork(worktree, directive) =>
        val where = if worktree then "a worktree" else "this workspace"
        val seed  = if directive.isEmpty then "Forked" else directive
        emit(HostMsg.UserMessage(TurnId("preview-turn"), seed))
        emit(HostMsg.AgentChunk(TurnId("preview-turn"), s"Forked into $where."))
        emit(HostMsg.TurnEnd(TurnId("preview-turn"), StopReason.EndTurn))
      case WebviewMsg.Send(text, _) =>
        SessionCommands.intercept(text) match
          case Some(cmd) if SessionCommands.isFork(cmd.name) =>
            Fork.parse(cmd.args) match
              case Left(err) =>
                emit(HostMsg.Error(err))
              case Right(args) if args.worktree.isEmpty =>
                emit(HostMsg.ForkAsk(args.directive.getOrElse("")))
              case Right(args) =>
                post(WebviewMsg.Fork(args.worktree.contains(true), args.directive.getOrElse("")))
          case _ =>
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
      case WebviewMsg.Queue(text, _) =>
        emit(HostMsg.Queued(List(QueuedPrompt(QueueId("preview-q"), text))))
      case WebviewMsg.QueueSendNow(_) | WebviewMsg.QueueDrop(_) =>
        ()
      case WebviewMsg.StopTask(id) =>
        emit(HostMsg.Tasks(List(TaskRow(id, TaskKind.Subagent, TaskStatus.Cancelled, "stopped"))))
      case WebviewMsg.PlanVerdict(_, _) =>
        emit(HostMsg.ClearCard(CardSlot.Plan))
      case WebviewMsg.PermissionChoice(_, _) | WebviewMsg.PermissionPark(_) =>
        emit(HostMsg.ClearCard(CardSlot.Permission))
      case WebviewMsg.QuestionSubmit(_, _) | WebviewMsg.QuestionDismiss(_) =>
        emit(HostMsg.ClearCard(CardSlot.Question))
      case WebviewMsg.ElicitAccept(_) | WebviewMsg.ElicitDecline(_) =>
        emit(HostMsg.ClearCard(CardSlot.Elicit))
      case WebviewMsg.Cancel =>
        emit(HostMsg.TurnEnd(TurnId("t2"), StopReason.EndTurn))
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
      case WebviewMsg.ResumeSession(id, _, hasHistory) =>
        currentId = id
        pickerOpen = false
        val title = sessions.find(_.id == id).map(_.title).getOrElse(id.value)
        emitMeta(id, title)
        if !hasHistory then
          emit(HostMsg.ClearTranscript)
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
        end if
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
      case WebviewMsg.MentionPick(_, _) | WebviewMsg.AddSelection | WebviewMsg.RemoveChip(_, _, _) =>
        ()
      case WebviewMsg.OpenFile(_, _) =>
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
      case WebviewMsg.Cancel =>
        emit(HostMsg.TurnEnd(TurnId("t-run"), StopReason.Cancelled))
        emit(HostMsg.TurnEnd(TurnId("t2"), StopReason.Cancelled))
      case WebviewMsg.CancelTurnChoice(_, keep) =>
        emit(HostMsg.TurnEnd(TurnId("t-run"), StopReason.Cancelled))
        emit(HostMsg.TurnEnd(TurnId("t2"), StopReason.Cancelled))
        if !keep then emit(HostMsg.Tasks(Nil))
      case WebviewMsg.AttachChild(id) =>
        emit(
          HostMsg.ChildTranscript(
            SessionId(id.value),
            List(
              TurnView(
                TurnId("child-t"),
                user = Some(TurnUser("research this")),
                agent = "Child is working.",
                stopReason = Some(StopReason.EndTurn),
              )
            ),
          )
        )
      case WebviewMsg.DetachChild             => ()
      case WebviewMsg.SteerChild(id, text, _) =>
        emit(
          HostMsg.ChildTranscript(
            SessionId(id.value),
            List(
              TurnView(
                TurnId("child-t"),
                user = Some(TurnUser("research this")),
                agent = "Child is working.",
                stopReason = Some(StopReason.EndTurn),
              ),
              TurnView(
                TurnId("child-steer"),
                user = Some(TurnUser(text)),
                agent = s"Heard: $text",
                stopReason = Some(StopReason.EndTurn),
              ),
            ),
          )
        )
      case WebviewMsg.ViewPlan =>
        emit(HostMsg.PlanView("# Plan\n\nUse Metals for compile."))
      case WebviewMsg.OpenPlan =>
        ()
      case WebviewMsg.OpenAgents =>
        emit(HostMsg.Agents(AgentsCatalog.builtins, List(PersonaDef("concise", "Be concise."))))
      case WebviewMsg.OpenDashboard =>
        emit(
          HostMsg.Dashboard(
            List(DashRow(SessionId("disk-1"), "Effect plan", "/repo", "idle", "Continue the plan", 10))
          )
        )
      case WebviewMsg.OpenWorkflows =>
        emit(HostMsg.Workflows(List(WorkflowRun("review-changes", "verify", "running", "2/4"))))
      case WebviewMsg.WorkflowControl(verb, name) =>
        emit(
          HostMsg.Workflows(
            WorkflowRuns.applyVerb(
              List(
                WorkflowRun("review-changes", "verify", "running", "2/4"),
                WorkflowRun("deep-research", "gather", "running", "1/3"),
              ),
              name,
              verb,
            )
          )
        )
      case WebviewMsg.OpenDoctor =>
        emit(
          HostMsg.DoctorReport(
            Doctor.collect(
              Some("/usr/bin/grok"),
              Some("1.0.24"),
              true,
              false,
              true,
              true,
              true,
              "/repo",
              2,
              "ready",
              Some("node"),
            )
          )
        )
      case WebviewMsg.OpenTheme => ()
      case WebviewMsg.Btw(text) =>
        emit(HostMsg.Btw(s"Aside: $text\n\nNoted.", done = true))
      case WebviewMsg.SetTheme(id) =>
        uiTheme = Theme.canonicalize(id)
        emit(HostMsg.UiPrefs(uiTheme, uiCompact, uiVim))
      case WebviewMsg.ToggleCompact =>
        uiCompact = !uiCompact
        emit(HostMsg.UiPrefs(uiTheme, uiCompact, uiVim))
      case WebviewMsg.ToggleVim =>
        uiVim = !uiVim
        emit(HostMsg.UiPrefs(uiTheme, uiCompact, uiVim))
      case WebviewMsg.AddImage(_, _, _) | WebviewMsg.RemoveImage(_) =>
        ()
      case WebviewMsg.PersistConfig(table, key, value) =>
        if table == "ui" then
          key match
            case "theme"        => uiTheme = Theme.canonicalize(value)
            case "compact_mode" => uiCompact = value == "true"
            case "vim_mode"     => uiVim = value == "true"
            case _              => ()
          emit(HostMsg.UiPrefs(uiTheme, uiCompact, uiVim))
    end match
  end post

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
