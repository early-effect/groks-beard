package groksbeard.ui

import ascent.*
import ascent.css.Color
import ascent.css.Styles.*
import ascent.dsl.*
import ascent.dsl.Arg
import groksbeard.core.*
import zio.*
import zio.stream.ZStream

enum OpenMenu:
  case Mode, Settings

enum Scene:
  case Empty, Slash, Mentions, Settings, Transcript, Permission, Plan, Question, Elicit, Changes

object Scene:
  def from(name: String): Scene =
    name match
      case "slash"      => Scene.Slash
      case "mentions"   => Scene.Mentions
      case "settings"   => Scene.Settings
      case "transcript" => Scene.Transcript
      case "permission" => Scene.Permission
      case "plan"       => Scene.Plan
      case "question"   => Scene.Question
      case "elicit"     => Scene.Elicit
      case "changes"    => Scene.Changes
      case _            => Scene.Empty
end Scene

object ChatApp:

  private val fg           = Color.Keyword("var(--vscode-foreground, #f3e6d0)")
  private val muted        = Color.Keyword("var(--vscode-descriptionForeground, #9d9488)")
  private val bg           = Color.Keyword("var(--vscode-sideBar-background, #1a1410)")
  private val inputBg      = Color.Keyword("var(--vscode-input-background, #2a1d16)")
  private val inputFg      = Color.Keyword("var(--vscode-input-foreground, #f3e6d0)")
  private val widgetBorder = Color.Keyword("var(--vscode-widget-border, #3d2a1f)")
  private val orange       = Color.hex("#c24e16")
  private val cream        = Color.hex("#f3e6d0")
  private val menuBg       = Color.Keyword("var(--vscode-menu-background, #2a1d16)")
  private val addFg        = Color.Keyword("var(--vscode-gitDecoration-addedResourceForeground, #3fb950)")
  private val delFg        = Color.Keyword("var(--vscode-gitDecoration-deletedResourceForeground, #f85149)")
  private val addBg        = Color.Keyword("var(--vscode-diffEditor-insertedTextBackground, rgba(63, 185, 80, 0.18))")
  private val delBg        = Color.Keyword("var(--vscode-diffEditor-removedTextBackground, rgba(248, 81, 73, 0.18))")

  object Page
      extends GlobalStyle(
        Selector(Elem.html, height.pct(100)),
        Selector(
          Elem.body,
          height.pct(100),
          margin.zero,
          color(fg),
          backgroundColor(bg),
          fontFamily.of(FontFamily.systemUi, FontFamily.sansSerif),
        ),
        Selector(Sel.id("root"), height.pct(100)),
      )

  object Shell
      extends CssClass(
        display.flex,
        flexDirection.column,
        height.pct(100),
        boxSizing.borderBox,
      )

  object Toolbar
      extends CssClass(
        display.flex,
        padding(8.px, 12.px),
        borderBottom(Border.solid(1.px, widgetBorder)),
      )

  object Chip
      extends CssClass(
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(4),
        padding(2.px, 8.px),
        backgroundColor(inputBg),
        color(fg),
        fontSize.px(12),
        cursor.pointer,
      )

  object Empty
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.center,
        paddingTop.px(48),
        minHeight.px(180),
      )

  object Logo extends CssClass(width.px(132), height.px(132))

  object Title
      extends CssClass(
        margin.zero,
        fontSize.px(20),
        fontWeight(600),
      )

  object Copy
      extends CssClass(
        margin.zero,
        color(muted),
        fontSize.px(13),
      )

  object Composer
      extends CssClass(
        display.flex,
        flexDirection.column,
        padding.px(12),
        borderTop(Border.solid(1.px, widgetBorder)),
      )

  object Draft
      extends CssClass(
        minHeight.px(40),
        width.pct(100),
        boxSizing.borderBox,
        fontFamily.inherit,
        color(inputFg),
        backgroundColor(inputBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding(8.px, 10.px),
      )

  object Send
      extends CssClass(
        border.none,
        borderRadius.px(6),
        padding(8.px, 14.px),
        cursor.pointer,
        fontWeight(600),
        color(cream),
        backgroundColor(orange),
      )

  object Popover
      extends CssClass(
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        margin.px(8),
        padding.px(4),
      )

  object MenuItem
      extends CssClass(
        display.flex,
        width.pct(100),
        border.none,
        backgroundColor(Color.transparent),
        color(fg),
        textAlign.left,
        padding(6.px, 8.px),
        cursor.pointer,
        fontSize.px(13),
      )

  object Transcript
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        overflowY.auto,
        padding.px(12),
        gap.px(12),
        minHeight.px(0),
      )

  object Turn extends CssClass(display.flex, flexDirection.column, gap.px(8))

  object UserMsg
      extends CssClass(
        alignSelf.end,
        backgroundColor(inputBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(8),
        padding(8.px, 10.px),
        fontSize.px(13),
        whiteSpace.preWrap,
      )

  object AgentMsg extends CssClass(fontSize.px(13), color(fg))

  object ThoughtBox
      extends CssClass(
        fontSize.px(12),
        color(muted),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(6),
      )

  object ToolBox
      extends CssClass(
        fontSize.px(12),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(6),
      )

  object Card
      extends CssClass(
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(8),
        margin.px(8),
        padding.px(10),
        display.flex,
        flexDirection.column,
        gap.px(8),
      )

  object Toast
      extends CssClass(
        display.flex,
        gap.px(8),
        padding(6.px, 12.px),
        fontSize.px(12),
        color(muted),
      )

  object StopReason extends CssClass(fontSize.px(12), color(muted))

  object ChangesPane
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(6),
        padding.px(10),
        borderTop(Border.solid(1.px, widgetBorder)),
        backgroundColor(menuBg),
      )

  object FileRow
      extends CssClass(
        display.flex,
        flexWrap.wrap,
        alignItems.center,
        gap.px(8),
        fontSize.px(12),
      )

  object StatAdd extends CssClass(color(addFg), fontWeight(600))

  object StatDel extends CssClass(color(delFg), fontWeight(600))

  object DiffPane
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(4),
        padding.px(10),
        borderTop(Border.solid(1.px, widgetBorder)),
        maxHeight.px(280),
        overflowY.auto,
        fontFamily.of(FontFamily.monospace),
        fontSize.px(12),
      )

  object AddLine
      extends CssClass(
        color(addFg),
        backgroundColor(addBg),
        whiteSpace.preWrap,
        padding(0.px, 6.px),
      )

  object DelLine
      extends CssClass(
        color(delFg),
        backgroundColor(delBg),
        whiteSpace.preWrap,
        padding(0.px, 6.px),
      )

  object CtxLine extends CssClass(whiteSpace.preWrap, color(muted), padding(0.px, 6.px))

  def component(
      bridge: HostBridge,
      logoSrc: Option[String],
      scene: Scene = Scene.Empty,
  ): ZIO[Scope, Nothing, ascent.ast.UI[Any]] =
    val initialDraft = scene match
      case Scene.Slash    => "/"
      case Scene.Mentions => "@"
      case _              => ""
    val initialMenu = scene match
      case Scene.Settings => Some(OpenMenu.Settings)
      case _              => None
    for
      chat       <- sq(PreviewScenes.seed(scene))
      draft      <- sq(initialDraft)
      dismissed  <- sq(false)
      mentionIdx <- sq(Option.empty[Int])
      openMenu   <- sq(initialMenu)
      bound      <- Promise.make[Nothing, Unit]
      _          <- ZStream
        .asyncScoped[Any, Nothing, HostMsg](
          emit =>
            ZIO.succeed {
              bridge.onHost(msg => emit(ZIO.succeed(Chunk.single(msg))))
              bridge.post(WebviewMsg.Ready)
              ComposerQuery.mentionQuery(initialDraft).foreach { q =>
                bridge.post(WebviewMsg.MentionQuery(q))
              }
            } *> bound.succeed(()),
          outputBuffer = 4096,
        )
        .mapZIO(msg => chat.update(ChatModel.applyMsg(_, msg)))
        .runDrain
        .forkScoped
      _ <- bound.await
    yield

      val slashShown = Squawk.zipWith(draft, chat) { (d, c) =>
        ComposerQuery.slashQuery(d).map(q => ComposerQuery.filterSlash(c.commands, q)).getOrElse(Nil)
      }
      val mentionShown = Squawk.zipWith(draft, Squawk.zipWith(chat, dismissed)(Tuple2.apply)) { (d, pack) =>
        val (c, disc) = pack
        ComposerQuery.mentionChoices(d, c.mentionQuery, c.mentionFiles, disc)
      }

      def sendDraft: UIO[Unit] =
        draft.get.flatMap { text =>
          val trimmed = text.trim
          if trimmed.isEmpty then ZIO.unit
          else
            chat.get.flatMap { c =>
              val msg =
                if ChatModel.turnIsRunning(c) then WebviewMsg.Queue(trimmed) else WebviewMsg.Send(trimmed)
              ZIO.succeed(bridge.post(msg)) *> draft.set("") *> dismissed.set(false)
            }
        }

      def pickSlash(name: String): UIO[Unit] =
        draft.set(s"/$name ") *>
          ZIO.succeed(bridge.post(WebviewMsg.SlashPick(name)))

      def pickMention(file: MentionFile): UIO[Unit] =
        draft.update { d =>
          d.replaceFirst("(?:^|\\s)@[^\\s]*$", " ").trim
        } *>
          dismissed.set(false) *>
          mentionIdx.set(None) *>
          ZIO.succeed(bridge.post(WebviewMsg.MentionPick(file.path, file.absPath))) *>
          ZIO.succeed(bridge.post(WebviewMsg.MentionQuery("")))

      def onDraft(text: String): UIO[Unit] =
        draft.set(text) *>
          openMenu.set(None) *>
          (ComposerQuery.mentionQuery(text) match
            case None =>
              dismissed.set(false) *> mentionIdx.set(None) *>
                ZIO.succeed(bridge.post(WebviewMsg.MentionQuery("")))
            case Some(q) =>
              dismissed.set(false) *> mentionIdx.set(Some(0)) *>
                ZIO.succeed(bridge.post(WebviewMsg.MentionQuery(q))))

      E.div(
        Shell,
        Page,
        E.div(
          Toolbar,
          E.button(
            Chip,
            TestId("mode"),
            A.title(chat.map(c => ModeLabel.modeTip(c.modeId))),
            Ev.onClick(_ =>
              openMenu.update {
                case Some(OpenMenu.Mode) => None
                case _                   => Some(OpenMenu.Mode)
              }
            ),
            chat.map(c => ModeLabel.modeLabel(c.modeId, c.modes)),
          ),
          E.button(
            Chip,
            TestId("settings"),
            Ev.onClick(_ =>
              openMenu.get.flatMap {
                case Some(OpenMenu.Settings) => openMenu.set(None)
                case _                       =>
                  openMenu.set(Some(OpenMenu.Settings)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.OpenSettings))
              }
            ),
            "Settings",
          ),
        ),
        when(chat.map(_.turns.isEmpty))(
          E.div(
            Empty,
            logoSrc match
              case Some(src) => E.img(Logo, A.src(src), A.alt("Grok's Beard"))
              case None      => E.span(),
            E.h1(Title, chat.map(_.title)),
            E.p(Copy, "Ask Grok anything."),
          )
        ),
        when(chat.map(_.turns.nonEmpty))(
          E.div(
            Transcript,
            TestId("transcript"),
            forEachSignal(chat.map(_.turns))(_.id) { (id, _, turn) =>
              renderTurn(bridge, id, turn)
            },
          )
        ),
        renderCards(bridge, chat),
        renderDiff(bridge, chat),
        renderChanges(bridge, chat),
        when(chat.map(c => c.queued > 0 || c.error.nonEmpty))(
          E.div(
            Toast,
            TestId("status"),
            chat.map { c =>
              c.error.getOrElse(if c.queued > 0 then s"${c.queued} queued" else "")
            },
          )
        ),
        when(openMenu.map(_.contains(OpenMenu.Mode)))(
          E.div(
            Popover,
            TestId("mode-menu"),
            forEach(chat.map(_.modes))(_.id) { mode =>
              E.button(
                MenuItem,
                TestId(s"mode-${mode.id}"),
                A.title(ModeLabel.modeTip(mode.id)),
                Ev.onClick(_ =>
                  openMenu.set(None) *>
                    chat.update(_.copy(modeId = mode.id)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetMode(mode.id)))
                ),
                mode.name,
              )
            },
          )
        ),
        when(openMenu.map(_.contains(OpenMenu.Settings)))(
          E.div(
            Popover,
            TestId("settings-panel"),
            E.button(
              MenuItem,
              TestId("setting-ctrl-enter"),
              Ev.onClick(_ =>
                chat.get.flatMap { c =>
                  val next = !c.settings.useCtrlEnterToSend
                  chat.update(_.copy(settings = c.settings.copy(useCtrlEnterToSend = next))) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetSetting("useCtrlEnterToSend", next)))
                }
              ),
              chat.map(c =>
                if c.settings.useCtrlEnterToSend then "Ctrl+Enter to send: on" else "Ctrl+Enter to send: off"
              ),
            ),
            E.button(
              MenuItem,
              TestId("setting-active-file"),
              Ev.onClick(_ =>
                chat.get.flatMap { c =>
                  val next = !c.settings.includeActiveFileByDefault
                  chat.update(_.copy(settings = c.settings.copy(includeActiveFileByDefault = next))) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetSetting("includeActiveFileByDefault", next)))
                }
              ),
              chat.map(c =>
                if c.settings.includeActiveFileByDefault then "Include active file: on" else "Include active file: off"
              ),
            ),
          )
        ),
        when(slashShown.map(_.nonEmpty))(
          E.ul(
            Popover,
            TestId("slash"),
            A.role("listbox"),
            forEach(slashShown)(_.name) { cmd =>
              E.li(
                E.button(
                  MenuItem,
                  TestId(s"slash-${cmd.name}"),
                  Ev.onClick(_ => pickSlash(cmd.name)),
                  cmd.name,
                )
              )
            },
          )
        ),
        when(mentionShown.map(_.nonEmpty))(
          E.ul(
            Popover,
            TestId("mentions"),
            A.role("listbox"),
            forEach(mentionShown)(_.absPath) { file =>
              E.li(
                E.button(
                  MenuItem,
                  TestId(s"mention-${file.path}"),
                  Ev.onClick(_ => pickMention(file)),
                  file.path,
                )
              )
            },
          )
        ),
        E.div(
          Composer,
          E.textarea(
            Draft,
            TestId("draft"),
            A.value(draft),
            A.placeholder("Message Grok"),
            A.title(
              chat.map { c =>
                if c.settings.useCtrlEnterToSend then "Ctrl/Cmd+Enter sends, Enter inserts a newline"
                else "Enter sends, Shift+Enter inserts a newline"
              }
            ),
            Events.onInput(e => onDraft(e.targetValue.getOrElse(""))),
            Ev.onKeyDown { (e: ascent.dom.KeyboardEvent) =>
              val key                         = e.key
              val ctrlOrMeta                  = e.ctrlKey || e.metaKey
              def go(z: UIO[Unit]): UIO[Unit] =
                e.preventDefault()
                z
              for
                slash    <- slashShown.get
                mentions <- mentionShown.get
                idx      <- mentionIdx.get
                s        <- chat.get.map(_.settings)
                out      <-
                  if key == "Escape" then go(openMenu.set(None) *> dismissed.set(true) *> mentionIdx.set(None))
                  else if mentions.nonEmpty && (key == "ArrowDown" || key == "ArrowUp") then
                    go(mentionIdx.set(ComposerQuery.moveMentionIndex(idx, key, mentions.size)))
                  else if mentions.nonEmpty && (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
                    mentions.lift(idx.getOrElse(0)) match
                      case Some(file) => go(pickMention(file))
                      case None       => ZIO.unit
                  else if slash.nonEmpty && key == "Enter" && !e.shiftKey && !ctrlOrMeta then
                    slash.headOption match
                      case Some(cmd) => go(pickSlash(cmd.name))
                      case None      => ZIO.unit
                  else
                    ComposerQuery.sendOnKey(key, e.shiftKey, ctrlOrMeta, s.useCtrlEnterToSend) match
                      case ComposerQuery.SendKey.Send    => go(sendDraft)
                      case ComposerQuery.SendKey.Newline => ZIO.unit
                      case ComposerQuery.SendKey.Ignore  => ZIO.unit
              yield out
              end for
            },
          ),
          E.button(
            Send,
            TestId("send"),
            A.`type`("button"),
            Ev.onClick(_ =>
              chat.get.flatMap { c =>
                if ChatModel.turnIsRunning(c) then ZIO.succeed(bridge.post(WebviewMsg.Cancel))
                else sendDraft
              }
            ),
            chat.map(c => if ChatModel.turnIsRunning(c) then "Stop" else "Send"),
          ),
        ),
      )
    end for
  end component

  private def renderTurn(bridge: HostBridge, id: String, turn: Squawk[TurnView]): ascent.ast.UI[Any] =
    E.section(
      Turn,
      TestId(s"turn-$id"),
      when(turn.map(_.user.exists(_.text.nonEmpty)))(
        E.div(UserMsg, TestId(s"user-$id"), turn.map(userTextOf))
      ),
      when(turn.map(_.thought.nonEmpty))(
        E.details(
          ThoughtBox,
          TestId(s"thought-$id"),
          E.summary(turn.map(t => Thought.summaryLabel(t.thought, t.stopReason.nonEmpty))),
          E.pre(turn.map(_.thought)),
        )
      ),
      turn.map(t => renderTools(bridge, t.tools)),
      when(turn.map(_.agent.nonEmpty))(
        E.div(AgentMsg, TestId(s"agent-$id"), turn.map(t => ChatMarkdown.render(t.agent)))
      ),
      when(turn.map(t => t.stopReason.exists(_ != "end_turn")))(
        E.div(StopReason, turn.map(_.stopReason.getOrElse("")))
      ),
    )
  end renderTurn

  private def userTextOf(turn: TurnView): String =
    turn.user
      .map { u =>
        val refs = u.chips.map(PromptChip.formatAtRef).filter(_.nonEmpty)
        (refs :+ u.text).filter(_.nonEmpty).mkString("\n")
      }
      .getOrElse("")

  private def renderTools(bridge: HostBridge, tools: List[ToolRow]): ascent.ast.UI[Any] =
    if tools.isEmpty then E.span()
    else
      val (earlier, visible)               = ToolView.splitTail(tools)
      val rolled: List[ascent.ast.UI[Any]] =
        if earlier.nonEmpty then
          List(
            E.details(
              ToolBox,
              E.summary(ToolView.rollupLabel(earlier.size)),
              Arg.ArgsArg(earlier.map(t => Arg.ChildArg(renderTool(bridge, t)))),
            )
          )
        else Nil
      E.div(Arg.ArgsArg((rolled ++ visible.map(t => renderTool(bridge, t))).map(Arg.ChildArg(_))))

  private def renderTool(bridge: HostBridge, tool: ToolRow): ascent.ast.UI[Any] =
    val stats =
      (tool.additions, tool.deletions) match
        case (Some(a), Some(d)) => Some((a, d))
        case _                  => None
    stats match
      case Some((a, d)) =>
        E.div(
          ToolBox,
          FileRow,
          TestId(s"tool-${tool.id}"),
          E.span(tool.title),
          statsEl(a, d),
          E.button(
            Chip,
            TestId(s"tool-diff-${tool.id}"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.OpenDiff(tool.id)))),
            "Review",
          ),
        )
      case None =>
        E.details(
          ToolBox,
          TestId(s"tool-${tool.id}"),
          E.summary(tool.title),
          tool.input.filter(_.nonEmpty).fold(E.span())(in => E.pre(ToolView.clip(in))),
          tool.output.filter(_.nonEmpty).fold(E.span())(out => E.pre(ToolView.clip(out))),
        )
    end match
  end renderTool

  private def renderCards(bridge: HostBridge, chat: ascent.Source[ChatModel]): ascent.ast.UI[Any] =
    E.div(
      TestId("cards"),
      forEach(chat.map(_.permission.toList))(_.requestId) { card =>
        val choices = card.options.zipWithIndex.map { (opt, idx) =>
          E.button(
            MenuItem,
            TestId(s"perm-${opt.optionId}"),
            A.title(ToolView.permissionTip(opt.name, opt.kind)),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PermissionChoice(card.requestId, opt.optionId)))),
            s"${idx + 1} ${opt.name}",
          )
        }
        val diff =
          if card.hasDiff then
            List(
              E.button(
                MenuItem,
                TestId("open-diff"),
                Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.OpenDiff(card.requestId)))),
                "Open diff",
              )
            )
          else Nil
        E.div(
          Card,
          TestId("permission"),
          E.h3(card.title),
          Arg.ArgsArg((choices ++ diff).map(Arg.ChildArg(_))),
        )
      },
      forEach(chat.map(_.plan.toList))(_.requestId) { card =>
        E.div(
          Card,
          TestId("plan"),
          E.pre(card.planMarkdown),
          E.button(
            Send,
            TestId("plan-approved"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, "approved")))),
            "Approve",
          ),
          E.button(
            MenuItem,
            TestId("plan-cancelled"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, "cancelled")))),
            "Request changes",
          ),
          E.button(
            MenuItem,
            TestId("plan-abandoned"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, "abandoned")))),
            "Abandon",
          ),
        )
      },
      forEach(chat.map(_.question.toList))(_.requestId) { card =>
        val body = card.questions.map { q =>
          E.div(
            E.p(q.prompt),
            Arg.ArgsArg(
              q.options.zipWithIndex.map { (opt, idx) =>
                Arg.ChildArg(
                  E.button(
                    MenuItem,
                    TestId(s"question-${q.id}-${opt.id}"),
                    Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.QuestionChoice(card.requestId, q.id, opt.id)))),
                    s"${idx + 1} ${opt.label}",
                  )
                )
              }
            ),
          )
        }
        val dismiss = E.button(
          MenuItem,
          TestId("question-dismiss"),
          Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.QuestionDismiss(card.requestId)))),
          "Dismiss",
        )
        E.div(Card, TestId("question"), Arg.ArgsArg((body :+ dismiss).map(Arg.ChildArg(_))))
      },
      forEach(chat.map(_.elicit.toList))(_.requestId) { card =>
        E.div(
          Card,
          TestId("elicit"),
          E.h3(card.title),
          E.button(
            Send,
            TestId("elicit-accept"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.ElicitAccept(card.requestId)))),
            "Accept",
          ),
          E.button(
            MenuItem,
            TestId("elicit-decline"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.ElicitDecline(card.requestId)))),
            "Decline",
          ),
        )
      },
    )

  private def renderChanges(bridge: HostBridge, chat: ascent.Source[ChatModel]): ascent.ast.UI[Any] =
    when(chat.map(_.changes.exists(_.files.nonEmpty)))(
      E.div(
        ChangesPane,
        TestId("changes"),
        E.div(
          FileRow,
          E.strong("Grok Changes"),
          chat.map { c =>
            c.changes.fold("")(s => ChangeSet.formatStats(s.additions, s.deletions))
          },
        ),
        forEach(chat.map(_.changes.toList.flatMap(_.files)))(_.path) { file =>
          val region = if file.wholeFile then "" else " region"
          val reason = file.undoDisabled.fold("")(r => s" · $r")
          E.div(
            FileRow,
            TestId(s"change-${UnifiedDiff.fileName(file.path)}"),
            E.span(s"${UnifiedDiff.fileName(file.path)} ${file.kind}$region$reason"),
            statsEl(file.additions, file.deletions),
            E.button(
              Chip,
              TestId(s"change-open-${UnifiedDiff.fileName(file.path)}"),
              Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.OpenDiff(file.path)))),
              "Open",
            ),
            E.button(
              Chip,
              TestId(s"change-keep-${UnifiedDiff.fileName(file.path)}"),
              Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.KeepChange(file.path)))),
              "Keep",
            ),
            if file.undoDisabled.isDefined then E.span(file.undoDisabled.getOrElse(""))
            else
              E.button(
                Chip,
                TestId(s"change-undo-${UnifiedDiff.fileName(file.path)}"),
                Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.UndoChange(file.path)))),
                "Undo",
              ),
          )
        },
      )
    )

  private def renderDiff(bridge: HostBridge, chat: ascent.Source[ChatModel]): ascent.ast.UI[Any] =
    when(chat.map(_.diff.nonEmpty))(
      E.div(
        DiffPane,
        TestId("diff"),
        E.div(
          FileRow,
          chat.map { c =>
            c.diff.fold("") { d =>
              val scope = if d.wholeFile then "whole file" else "region"
              s"${UnifiedDiff.fileName(d.path)} ($scope)"
            }
          },
          E.button(
            Chip,
            TestId("diff-close"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.CloseDiff))),
            "Close",
          ),
        ),
        forEach(chat.map(_.diff.toList.flatMap(d => UnifiedDiff.lines(d.oldText, d.newText).zipWithIndex)))(
          _._2.toString
        ) { pair =>
          val (line, idx) = pair
          line match
            case DiffLine.Add(text)     => E.div(AddLine, TestId(s"diff-add-$idx"), s"+ $text")
            case DiffLine.Del(text)     => E.div(DelLine, TestId(s"diff-del-$idx"), s"- $text")
            case DiffLine.Context(text) => E.div(CtxLine, TestId(s"diff-ctx-$idx"), s"  $text")
        },
      )
    )

  private def statsEl(add: Int, del: Int): ascent.ast.UI[Any] =
    E.span(E.span(StatAdd, s"+$add"), E.span(StatDel, s"/-$del"))
end ChatApp
