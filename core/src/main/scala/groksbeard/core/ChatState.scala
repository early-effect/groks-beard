package groksbeard.core

import zio.*
import zio.json.ast.Json

/** In-flight chat identity. Persisted chrome lives on [[UiPrefs]], not here. */
final case class ChatState(
    rpcId: Int = 0,
    turnSeq: Int = 0,
    currentTurn: TurnId = TurnId.mint(0),
    currentTitle: String = "Untitled",
    sessionId: Option[SessionId] = None,
    modeId: ModeId = ModeId.Normal,
    modes: List[ModeOption] = Nil,
    modelId: ModelId = ModelId.empty,
    models: List[ModelOption] = Nil,
    effort: String = "",
    running: Boolean = false,
    chips: List[PromptChip] = Nil,
    pendingQueue: Vector[QueuedPrompt] = Vector.empty,
    queueSeq: Int = 0,
    settingsState: SettingsState = SettingsState.defaults,
    occupancy: Option[Occupancy] = None,
    pendingPerm: Map[RequestId, Json] = Map.empty,
    inbound: Map[RequestId, RpcId] = Map.empty,
    pendingMethod: Map[RpcId, AcpMethod] = Map.empty,
    loading: Boolean = false,
    loadCleared: Boolean = false,
    loadModel: ChatModel = ChatModel.empty,
    pendingResume: Option[SessionId] = None,
    pendingLoad: Map[RpcId, SessionId] = Map.empty,
    pendingRewind: Option[Int] = None,
    cancelledLoads: Set[SessionId] = Set.empty,
    inboundEpoch: Int = 0,
    listOpen: Boolean = false,
    listed: List[SessionRow] = Nil,
    live: Boolean = false,
    initializeSent: Boolean = false,
    initialized: Boolean = false,
    agentGone: Boolean = false,
    liveExecute: Option[ToolCallId] = None,
    tasks: List[TaskRow] = Nil,
    loopSeq: Int = 0,
    loopFibers: Map[String, Fiber.Runtime[Nothing, Unit]] = Map.empty,
    canWorktree: Boolean = false,
    sessionCwd: String = ".",
    pendingForkPrompt: Option[String] = None,
    configOptions: List[ConfigOption] = Nil,
    agentCaps: AgentCapabilities = AgentCapabilities(),
    pendingImages: List[ImageChip] = Nil,
    imageSeq: Int = 0,
    childTurns: Map[String, List[TurnView]] = Map.empty,
    restoreCodeNext: Boolean = false,
    promptSid: Option[SessionId] = None,
    commands: List[SlashCommand] = Nil,
    workflows: List[WorkflowRun] = Nil,
    slashPass: Set[RpcId] = Set.empty,
    userOpen: Boolean = false,
    pendingBtw: Option[String] = None,
    lastPlan: Option[String] = None,
):
  def sid(fallback: SessionId): SessionId = sessionId.getOrElse(fallback)

  def currentModel: Option[ModelOption] = models.find(_.modelId == modelId)

  def bumpEpoch: ChatState = copy(inboundEpoch = inboundEpoch + 1)

  def takeRpc(method: AcpMethod, loadSessionId: Option[SessionId]): (RpcId, ChatState) =
    val n    = rpcId + 1
    val id   = RpcId.Num(n)
    val next = copy(
      rpcId = n,
      pendingMethod = pendingMethod.updated(id, method),
      pendingLoad = loadSessionId.fold(pendingLoad)(sid => pendingLoad.updated(id, sid)),
    )
    (id, next)

  def resetTurn: ChatState = copy(
    turnSeq = 0,
    currentTurn = TurnId.mint(0),
    currentTitle = "Untitled",
    chips = Nil,
    pendingQueue = Vector.empty,
    queueSeq = 0,
    occupancy = None,
    running = false,
    liveExecute = None,
    loadModel = ChatModel.empty,
    tasks = Nil,
    workflows = Nil,
    slashPass = Set.empty,
    userOpen = false,
    pendingBtw = None,
    lastPlan = None,
  )

  def resetLocal: ChatState =
    resetTurn.copy(loading = false, loadCleared = false, pendingResume = None)

  def cancelPendingResume: ChatState =
    pendingResume match
      case None     => this
      case Some(id) => copy(cancelledLoads = cancelledLoads + id)

  def beginResume(id: SessionId): ChatState =
    resetTurn.copy(
      cancelledLoads = cancelledLoads - id,
      sessionId = Some(id),
      loading = true,
      loadCleared = true,
      pendingResume = Some(id),
      restoreCodeNext = false,
    )

  def bumpTurn: ChatState =
    val n = turnSeq + 1
    copy(turnSeq = n, currentTurn = TurnId.mint(n))

  /** Consecutive `user_message_chunk`s (text then image) stay on one turn. A later prompt opens a new one. */
  def noteUserPrompt: ChatState =
    if userOpen then copy(running = true)
    else bumpTurn.copy(running = true, userOpen = true)

  def closeUserPrompt: ChatState =
    copy(userOpen = false)

  def startTurn(text: String, chosen: List[PromptChip], fallback: SessionId): ChatState =
    val n = turnSeq + 1
    copy(
      running = true,
      userOpen = true,
      liveExecute = None,
      turnSeq = n,
      currentTurn = TurnId.mint(n),
      currentTitle =
        ChangeSet.turnTitle(if text.nonEmpty then text else chosen.headOption.map(_.path).getOrElse("chip")),
      promptSid = Some(sessionId.getOrElse(fallback)),
    )
  end startTurn

  def enqueue(text: String, chosen: List[PromptChip], images: List[ImageChip]): ChatState =
    val n = queueSeq + 1
    copy(
      chips = Nil,
      pendingImages = Nil,
      queueSeq = n,
      pendingQueue = pendingQueue :+ QueuedPrompt(QueueId.mint(n), text, chosen, images),
    )

  def dequeue: (Option[QueuedPrompt], ChatState) =
    if pendingQueue.isEmpty then (None, this)
    else (Some(pendingQueue.head), copy(pendingQueue = pendingQueue.tail))

  def focusedTitle(product: String): String =
    sessionId
      .flatMap(id => listed.find(_.id == id))
      .map(SessionIndex.displayTitle)
      .filter(t => t.nonEmpty && t != product)
      .getOrElse("")

  def withSession(decoded: SessionNewResult, raw: Json): ChatState =
    val base      = copy(sessionId = Some(decoded.sessionId))
    val withModes = decoded.modes match
      case None        => base
      case Some(state) =>
        base.copy(
          modeId = state.currentModeId,
          modes = if state.availableModes.nonEmpty then state.availableModes else base.modes,
        )
    val withModels = decoded.models match
      case None        => withModes
      case Some(state) =>
        val next = withModes.copy(
          modelId = state.currentModelId,
          models = if state.availableModels.nonEmpty then state.availableModels else withModes.models,
        )
        next.copy(effort = Effort.activeOf(next.currentModel))
    withModels.withConfig(decoded.configOptions ++ ConfigOption.of(raw))
  end withSession

  def withConfig(opts: List[ConfigOption]): ChatState =
    if opts.isEmpty then this
    else
      val catalog = ConfigOption.models(opts)
      copy(
        configOptions = opts,
        modelId = ConfigOption.modelId(opts).getOrElse(modelId),
        models = if catalog.nonEmpty then catalog else models,
        effort = ConfigOption.effort(opts).getOrElse(effort),
      )

  def noteLiveTool(row: ToolRow): ChatState =
    if ToolStatus.isLive(row.status) && ChatState.isExecuteTool(row) then copy(liveExecute = Some(row.id))
    else if liveExecute.contains(row.id) && !ToolStatus.isLive(row.status) then copy(liveExecute = None)
    else this

  def recordInbound(reqId: RequestId, rid: RpcId): ChatState =
    copy(inbound = inbound.updated(reqId, rid))

  def dropInbound(reqId: RequestId): ChatState =
    copy(inbound = inbound - reqId, pendingPerm = pendingPerm - reqId)

  def takeInbound(reqId: RequestId): (Option[RpcId], ChatState) =
    inbound.get(reqId) match
      case None     => (None, this)
      case Some(id) => (Some(id), dropInbound(reqId))

  def parkPermission(reqId: RequestId, params: Json): ChatState =
    copy(pendingPerm = pendingPerm.updated(reqId, params))

  def addImage(mime: String, data: String, name: String): ChatState =
    val n = imageSeq + 1
    copy(imageSeq = n, pendingImages = pendingImages :+ ImageAttach.mint(n, mime, data, name))

  def noteUserWhileLoading: ChatState =
    noteUserPrompt.copy(loadCleared = true)

  def markAgentGone: ((Boolean, Option[SessionId], Boolean, TurnId), ChatState) =
    if agentGone then ((true, None, false, currentTurn), this)
    else
      val locked    = pendingResume
      val afterLoad = pendingResume match
        case None     => this
        case Some(id) =>
          copy(
            cancelledLoads = cancelledLoads + id,
            loading = false,
            pendingResume = None,
            pendingLoad = Map.empty,
          )
      val wasRunning = afterLoad.running
      val turn       = afterLoad.currentTurn
      ((false, locked, wasRunning, turn), afterLoad.copy(agentGone = true, running = false))

  def foldChild(sid: SessionId, msg: HostMsg): (List[TurnView], ChatState) =
    val key   = sid.value
    val prior = childTurns.getOrElse(key, Nil)
    val next  = ChildAttach.fold(prior, msg)
    (next, copy(childTurns = childTurns.updated(key, next)))

  def foldChildMsgs(sid: SessionId, msgs: List[HostMsg]): (List[TurnView], ChatState) =
    msgs.foldLeft((childTurns.getOrElse(sid.value, Nil), this)) { case ((turns, st), msg) =>
      val next = ChildAttach.fold(turns, msg)
      (next, st.copy(childTurns = st.childTurns.updated(sid.value, next)))
    }

  def popMethod(id: RpcId): (Option[AcpMethod], ChatState) =
    val method = pendingMethod.get(id)
    (method, copy(pendingMethod = pendingMethod - id))

  def popLoad(id: RpcId): (Option[SessionId], ChatState) =
    val sid = pendingLoad.get(id)
    (sid, copy(pendingLoad = pendingLoad - id))

  def mintLoop(spec: LoopSpec): ((TaskId, List[TaskRow]), ChatState) =
    val n   = loopSeq + 1
    val id  = TaskId.mint(n)
    val row =
      TaskRow(
        id = id,
        kind = TaskKind.Loop,
        status = TaskStatus.Running,
        label = spec.prompt,
        detail = spec.human,
        owned = true,
      )
    val next = Tasks.upsert(tasks, row)
    ((id, next), copy(loopSeq = n, tasks = next))
  end mintLoop
end ChatState

object ChatState:
  def seed(cwd: String, settings: SettingsState, modes: List[ModeOption]): ChatState =
    ChatState(sessionCwd = cwd, settingsState = settings, modes = modes)

  def isExecuteTool(row: ToolRow): Boolean =
    row.kind == ToolKind.Execute ||
      row.title.contains("terminal") ||
      row.title.toLowerCase.contains("execute")
end ChatState
