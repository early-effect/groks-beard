package groksbeard.ui

import ascent.*
import ascent.ast.Attr
import ascent.css.Color
import ascent.css.Styles.*
import ascent.domtypes.AttrValue
import ascent.dsl.*
import ascent.dsl.Arg
import groksbeard.core.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import zio.*
import zio.stream.ZStream

enum OpenMenu:
  case Mode, Settings, Model

final case class SessionLeave(id: String, fromPicker: Boolean)

enum Scene:
  case Empty, Slash, Mentions, Settings, Transcript, Permission, Plan, Question, Elicit, Changes, Resume

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
      case "resume"     => Scene.Resume
      case _            => Scene.Empty
end Scene

object ChatApp:

  private val LeaveMs      = 320L
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
        Selector(
          Elem.html,
          position.fixed,
          top.px(0),
          right.px(0),
          bottom.px(0),
          left.px(0),
          overflow.hidden,
          backgroundColor(bg),
        ),
        Selector(
          Elem.body,
          position.fixed,
          top.px(0),
          right.px(0),
          bottom.px(0),
          left.px(0),
          margin.zero,
          overflow.hidden,
          color(fg),
          backgroundColor(bg),
          fontFamily.of(FontFamily.systemUi, FontFamily.sansSerif),
        ),
        Selector(
          Sel.id("root"),
          position.fixed,
          top.px(0),
          right.px(0),
          bottom.px(0),
          left.px(0),
          overflow.hidden,
        ),
      )

  object Shell
      extends CssClass(
        display.flex,
        flexDirection.column,
        width.pct(100),
        height.pct(100),
        overflow.hidden,
        boxSizing.borderBox,
        position.relative,
      )

  object Toolbar
      extends CssClass(
        display.flex,
        alignItems.center,
        gap.px(6),
        flexShrink(0),
        padding(8.px, 12.px),
        borderBottom(Border.solid(1.px, widgetBorder)),
      )

  object Stage
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        minHeight.px(0),
        overflowY.auto,
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

  object ChipRow
      extends CssClass(
        display.flex,
        flexWrap.wrap,
        gap.px(6),
        marginBottom.px(8),
      )

  object ChipRemove
      extends CssClass(
        border.none,
        backgroundColor(Color.transparent),
        color(muted),
        cursor.pointer,
        padding(0.px, 4.px),
        fontSize.px(14),
      )

  object OccupancyMeter
      extends CssClass(
        display.flex,
        alignItems.center,
        gap.px(6),
        minWidth.px(0),
        flexGrow(1.0),
        color(muted),
        fontSize.px(11),
      )

  object OccupancyTrack
      extends CssClass(
        width.px(36),
        height.px(4),
        borderRadius.px(2),
        backgroundColor(widgetBorder),
        overflow.hidden,
        flexShrink(0),
      )

  object OccupancyFill
      extends CssClass(
        height.pct(100),
        backgroundColor(Color.hex("#6b4a32")),
      )

  object OccupancyFillWarn extends CssClass(backgroundColor(orange))

  object OccupancyFillHot extends CssClass(backgroundColor(Color.hex("#a33b12")))

  object OccupancyCopy
      extends CssClass(
        overflow.hidden,
        fontSize.px(11),
        color(muted),
      )

  object SessionChip
      extends CssClass(
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(4),
        padding(2.px, 8.px),
        backgroundColor(inputBg),
        color(fg),
        fontSize.px(12),
        cursor.pointer,
        overflow.hidden,
        maxWidth.px(180),
        flexShrink(1),
        textAlign.left,
        whiteSpace.nowrap,
        textOverflow.ellipsis,
      )

  object ToolbarGrow
      extends CssClass(
        flexGrow(1.0),
        minWidth.px(0),
      )

  object Picker
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        minHeight.px(0),
        overflow.hidden,
        padding.px(8),
        gap.px(6),
      )

  object PickerHead
      extends CssClass(
        display.flex,
        alignItems.center,
        justifyContent.spaceBetween,
        width.pct(100),
        flexShrink(0),
      )

  object PickerList
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        minHeight.px(0),
        overflowY.auto,
      )

  object Filter
      extends CssClass(
        height.px(32),
        minHeight.px(32),
        maxHeight.px(32),
        width.pct(100),
        boxSizing.borderBox,
        fontFamily.inherit,
        fontSize.px(13),
        color(inputFg),
        backgroundColor(inputBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding(6.px, 8.px),
        flexShrink(0),
      )

  object SessionRowEl
      extends CssClass(
        display.flex,
        flexDirection.row,
        alignItems.center,
        width.pct(100),
        gap.px(8),
        boxSizing.borderBox,
      )

  object SessionItem
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.flexStart,
        flexGrow(1.0),
        minWidth.px(0),
        boxSizing.borderBox,
        border.none,
        backgroundColor(Color.transparent),
        color(fg),
        textAlign.left,
        padding(8.px, 8.px),
        cursor.pointer,
        fontSize.px(13),
        gap.px(2),
        overflow.hidden,
      )

  object SessionFade
      extends Keyframes(
        Frame.from(opacity(1), transform(Transform.translateY(0.px))),
        Frame.to(opacity(0), transform(Transform.translateY((-8).px))),
      )

  object SessionLeaving
      extends CssClass(
        SessionFade.use(
          Time.ms(320),
          TimingFunction.easeOut,
          fill = Some(SingleAnimationFillMode.Forwards),
        ),
        pointerEvents.none,
        MediaQuery(Media.prefersReducedMotion.reduce, animation.none.important, opacity(0)),
      )

  object SessionTitle
      extends CssClass(
        width.pct(100),
        overflow.hidden,
        whiteSpace.nowrap,
      )

  object SessionMetaLine
      extends CssClass(
        fontSize.px(11),
        color(muted),
      )

  object WelcomePane
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        minHeight.px(0),
        overflow.hidden,
      )

  object WelcomeList
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.stretch,
        width.pct(100),
        flexGrow(1.0),
        minHeight.px(0),
        overflowY.auto,
        padding(8.px, 12.px, 12.px, 12.px),
        boxSizing.borderBox,
      )

  object EmptyHero
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.center,
        flexShrink(0),
        padding(16.px, 16.px),
      )

  object Empty
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.center,
        justifyContent.center,
        flexGrow(1.0),
        minHeight.px(0),
        overflowY.auto,
        padding.px(16),
      )

  object Logo
      extends CssClass(
        display.block,
        width.px(128),
        height.px(128),
        flexShrink(0),
        objectFit.contain,
      )

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
        flexShrink(0),
        padding.px(12),
        borderTop(Border.solid(1.px, widgetBorder)),
      )

  object ComposerBar
      extends CssClass(
        display.flex,
        alignItems.center,
        justifyContent.flexEnd,
        gap.px(8),
        marginTop.px(8),
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
        position.absolute,
        top.px(40),
        left.px(12),
        zIndex(20),
        minWidth.px(200),
        maxWidth.px(280),
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(4),
        boxSizing.borderBox,
      )

  object PopoverEnd
      extends CssClass(
        position.absolute,
        top.px(40),
        right.px(12),
        left.auto,
        zIndex(20),
        minWidth.px(220),
        maxWidth.px(320),
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(4),
        boxSizing.borderBox,
      )

  object ComposerMenu
      extends CssClass(
        alignSelf.flexStart,
        minWidth.px(200),
        maxWidth.px(280),
        margin(0.px, 12.px, 8.px, 12.px),
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(4),
        boxSizing.borderBox,
        zIndex(10),
      )

  object MenuItem
      extends CssClass(
        display.block,
        width.pct(100),
        boxSizing.borderBox,
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

  object Cards
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.center,
        width.pct(100),
        flexShrink(0),
      )

  object Card
      extends CssClass(
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(8),
        margin(8.px, 12.px),
        padding.px(10),
        display.flex,
        flexDirection.column,
        flexShrink(0),
        gap.px(8),
        maxWidth.px(440),
        width.pct(100),
        alignSelf.center,
        boxSizing.borderBox,
      )

  object CardBtn
      extends CssClass(
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(4),
        padding(6.px, 8.px),
        backgroundColor(inputBg),
        color(fg),
        cursor.pointer,
        textAlign.left,
        fontSize.px(13),
      )

  object Toast
      extends CssClass(
        display.flex,
        gap.px(8),
        padding(6.px, 12.px),
        fontSize.px(12),
        color(muted),
      )

  object SpinFade
      extends Keyframes(
        Frame.from(opacity(0)),
        Frame.pct(8)(opacity(1)),
        Frame.pct(22)(opacity(1)),
        Frame.pct(30)(opacity(0)),
        Frame.to(opacity(0)),
      )

  object ActivityRow
      extends CssClass(
        display.flex,
        alignItems.center,
        gap.px(8),
        fontSize.px(12),
        color(muted),
        marginBottom.px(8),
        MediaQuery(Media.prefersReducedMotion.reduce, animation.none.important),
      )

  object ActivityIcon
      extends CssClass(
        position.relative,
        display.inlineBlock,
        width.px(14),
        height.px(14),
        flexShrink(0),
        fontSize.px(14),
        lineHeight(1),
        Selector(":nth-child(1)", animationDelay.s(0)),
        Selector(":nth-child(2)", animationDelay.s(0.25)),
        Selector(":nth-child(3)", animationDelay.s(0.5)),
        Selector(":nth-child(4)", animationDelay.s(0.75)),
      )

  object SpinGlyph
      extends CssClass(
        position.absolute,
        left.px(0),
        top.px(0),
        SpinFade.use(
          Time.s(1),
          TimingFunction.linear,
          iterations = Some(SingleAnimationIterationCount.Infinite),
        ),
        MediaQuery(Media.prefersReducedMotion.reduce, animation.none.important),
      )

  object ActivityThink  extends CssClass(color(Color.hex("#c4b5fd")))
  object ActivityEdit   extends CssClass(color(orange))
  object ActivityRead   extends CssClass(color(Color.hex("#58a6ff")))
  object ActivityRun    extends CssClass(color(Color.hex("#3fb950")))
  object ActivitySearch extends CssClass(color(Color.hex("#22d3ee")))
  object ActivityDelete extends CssClass(color(Color.hex("#f85149")))
  object ActivityMove   extends CssClass(color(Color.hex("#d2a8ff")))
  object ActivityWait   extends CssClass(color(orange))
  object ActivityOther  extends CssClass(color(muted))

  object StopReason extends CssClass(fontSize.px(12), color(muted))

  object ChangesPane
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(6),
        padding.px(10),
        borderTop(Border.solid(1.px, widgetBorder)),
        backgroundColor(menuBg),
        flexShrink(0),
      )

  object ChangesHead
      extends CssClass(
        display.flex,
        flexWrap.wrap,
        alignItems.center,
        gap.px(8),
        width.pct(100),
        boxSizing.borderBox,
        border.none,
        backgroundColor(Color.transparent),
        color(fg),
        cursor.pointer,
        padding.zero,
        textAlign.left,
        fontSize.px(12),
      )

  object ChangesList
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(6),
        maxHeight.px(180),
        minHeight.px(0),
        overflowY.auto,
      )

  object ChangesTurn
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(6),
        width.pct(100),
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

  private def wallMs: UIO[Long] = Clock.currentTime(TimeUnit.MILLISECONDS)

  private def adoptView(c: ChatModel, id: String, waiting: AtomicReference[Option[String]]): ChatModel =
    val title =
      if id.isEmpty then "Grok's Beard"
      else c.sessions.find(_.id == id).map(SessionIndex.displayTitle).getOrElse(c.title)
    waiting.set(Some(id))
    ChatModel.adopt(c, id, title)

  private def commit(
      hist: History,
      lastHref: Ref[String],
      rel: String,
      msg: WebviewMsg,
      bridge: HostBridge,
  ): UIO[Unit] =
    hist.location.get.flatMap { cur =>
      val next = cur.resolve(rel)
      lastHref.set(next.href) *> hist.push(next) *> ZIO.succeed(bridge.post(msg))
    }

  def component(
      bridge: HostBridge,
      logoSrc: Option[String],
      scene: Scene = Scene.Empty,
  ): ZIO[Scope, Nothing, ascent.ast.UI[Any]] =
    History.memory().flatMap(h => component(bridge, logoSrc, h, scene))

  def component(
      bridge: HostBridge,
      logoSrc: Option[String],
      hist: History,
      scene: Scene,
  ): ZIO[Scope, Nothing, ascent.ast.UI[Any]] =
    val initialDraft = scene match
      case Scene.Slash    => "/"
      case Scene.Mentions => "@"
      case _              => ""
    val initialMenu = scene match
      case Scene.Settings => Some(OpenMenu.Settings)
      case _              => None
    for
      chat          <- sq(PreviewScenes.seed(scene))
      draft         <- sq(initialDraft)
      dismissed     <- sq(false)
      mentionIdx    <- sq(Option.empty[Int])
      openMenu      <- sq(initialMenu)
      pickerQuery   <- sq("")
      changesOpen   <- sq(false)
      leaving       <- sq(Option.empty[SessionLeave])
      pendingDelete <- sq(Option.empty[String])
      nowMs         <- wallMs.flatMap(sq(_))
      lastHref      <- Ref.make("")
      bound         <- Promise.make[Nothing, Unit]
      waiting  = new AtomicReference(Option.empty[String])
      leaveGen = new AtomicInteger(0)
      _ <- ZStream
        .tick(1.second)
        .mapZIO(_ => wallMs.flatMap(nowMs.set))
        .runDrain
        .forkScoped
      _ <- ZStream
        .asyncScoped[Any, Nothing, HostMsg](
          emit =>
            ZIO.succeed {
              bridge.onHost { msg =>
                val want = waiting.get()
                if ChatModel.dropHost(want, msg) then ()
                else
                  if ChatModel.catchesUp(want, msg) then waiting.set(None)
                  emit(ZIO.succeed(Chunk.single(msg)))
              }
              bridge.post(WebviewMsg.Ready)
              ComposerQuery.mentionQuery(initialDraft).foreach { q =>
                bridge.post(WebviewMsg.MentionQuery(q))
              }
            } *> bound.succeed(()),
          outputBuffer = 4096,
        )
        .mapZIO(msg => wallMs.flatMap(now => chat.update(ChatModel.applyMsg(_, msg, now))))
        .runDrain
        .forkScoped
      _ <- bound.await
      _ <- hist.location.get.flatMap(loc => lastHref.set(loc.href))
      _ <- hist.location.get.flatMap { loc =>
        BeardPath.sessionId(loc) match
          case Some(id) =>
            chat.update(adoptView(_, id, waiting)) *> ZIO.succeed(bridge.post(WebviewMsg.ResumeSession(id)))
          case None => ZIO.unit
      }
      locSub <- hist.location.observe { loc =>
        lastHref.get.flatMap { prev =>
          if prev == loc.href then ZIO.unit
          else
            lastHref.set(loc.href) *> (
              BeardPath.sessionId(loc) match
                case Some(id) =>
                  chat.update(adoptView(_, id, waiting)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.ResumeSession(id)))
                case None =>
                  leaving.set(None) *>
                    chat.update(adoptView(_, "", waiting)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.NewSession))
            )
        }
      }
      _ <- ZIO.addFinalizer(locSub.cancel)
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
          chat.get.flatMap { c =>
            val trimmed = text.trim
            SessionCommands.intercept(trimmed) match
              case Some(cmd) if SessionCommands.isNew(cmd.name) =>
                draft.set("") *>
                  leaving.set(None) *>
                  chat.update(adoptView(_, "", waiting)) *>
                  commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.NewSession, bridge)
              case Some(cmd) if SessionCommands.isResume(cmd.name) || SessionCommands.isHome(cmd.name) =>
                draft.set("") *> openPicker
              case Some(cmd) if SessionCommands.isModel(cmd.name) && cmd.args.isEmpty =>
                draft.set("") *> openMenu.set(Some(OpenMenu.Model))
              case Some(cmd) if SessionCommands.isModel(cmd.name) =>
                ModelOption.pick(cmd.args, c.models) match
                  case Some(m) =>
                    draft.set("") *>
                      chat.update(_.copy(modelId = m.modelId, error = None)) *>
                      ZIO.succeed(bridge.post(WebviewMsg.SetModel(m.modelId)))
                  case None =>
                    draft.set("") *> chat.update(_.copy(error = Some(s"Unknown model: ${cmd.args}")))
              case Some(cmd) if SessionCommands.isRename(cmd.name) =>
                SessionEdit.parseRename(cmd.args) match
                  case Left("empty") =>
                    val shown = sessionLabel(c)
                    val seed  =
                      if shown == "Resume" || shown == "This session" || shown == "Untitled session" then ""
                      else shown
                    draft.set(if seed.isEmpty then "/rename " else s"/rename $seed")
                  case Left(err) =>
                    draft.set("") *> chat.update(_.copy(error = Some(err)))
                  case Right(op) =>
                    draft.set("") *> applyRename(c.sessionId, op)
              case Some(cmd) if SessionCommands.isDelete(cmd.name) =>
                draft.set("") *> armDelete(c.sessionId)
              case _ if trimmed.isEmpty && c.chips.isEmpty =>
                ZIO.unit
              case _ =>
                val msg =
                  if ChatModel.turnIsRunning(c) then WebviewMsg.Queue(trimmed) else WebviewMsg.Send(trimmed)
                ZIO.succeed(bridge.post(msg)) *> draft.set("") *> dismissed.set(false)
            end match
          }
        }

      def dropChip(chip: PromptChip): UIO[Unit] =
        chat.update(m => m.copy(chips = m.chips.filterNot(PromptChip.sameRange(_, chip)))) *>
          ZIO.succeed(bridge.post(WebviewMsg.RemoveChip(chip.absPath, chip.startLine, chip.endLine)))

      def parkPermission: UIO[Unit] =
        chat.get.flatMap { c =>
          c.permission match
            case None       => ZIO.unit
            case Some(card) =>
              chat.update(_.copy(permission = None)) *>
                ZIO.succeed(bridge.post(WebviewMsg.PermissionPark(card.requestId)))
        }

      def onCardKey(e: ascent.dom.KeyboardEvent): UIO[Unit] =
        val key        = e.key
        val ctrlOrMeta = e.ctrlKey || e.metaKey
        chat.get.flatMap { c =>
          if key == "Escape" then
            e.preventDefault()
            openMenu.get.flatMap {
              case Some(_) => openMenu.set(None)
              case None    =>
                pendingDelete.get.flatMap {
                  case Some(_) => cancelDelete
                  case None    =>
                    mentionShown.get.flatMap { mentions =>
                      if mentions.nonEmpty then dismissed.set(true) *> mentionIdx.set(None)
                      else if c.pickerOpen then closePicker
                      else if c.permission.isDefined then parkPermission
                      else if ChatModel.turnIsRunning(c) then ZIO.succeed(bridge.post(WebviewMsg.Cancel))
                      else ZIO.unit
                    }
                }
            }
          else if e.shiftKey && key == "Tab" && !ctrlOrMeta then
            e.preventDefault()
            openMenu.set(None) *> ZIO.succeed(bridge.post(WebviewMsg.CycleMode))
          else
            pendingDelete.get.flatMap {
              case Some(_) if key == "y" || key == "Y" =>
                e.preventDefault()
                confirmDelete
              case Some(_) if key == "n" || key == "N" =>
                e.preventDefault()
                cancelDelete
              case _ =>
                c.permission.flatMap(p => ComposerQuery.permissionOption(key, p.options)) match
                  case Some(opt) =>
                    e.preventDefault()
                    ZIO.succeed(bridge.post(WebviewMsg.PermissionChoice(c.permission.get.requestId, opt.optionId)))
                  case None =>
                    c.question.flatMap(q => ComposerQuery.questionOption(key, q.questions)) match
                      case Some((qid, oid)) =>
                        e.preventDefault()
                        ZIO.succeed(bridge.post(WebviewMsg.QuestionChoice(c.question.get.requestId, qid, oid)))
                      case None => ZIO.unit
            }
        }
      end onCardKey

      def startNew: UIO[Unit] =
        leaving.set(None) *>
          chat.update(adoptView(_, "", waiting)) *>
          commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.NewSession, bridge)

      def openSession(id: String): UIO[Unit] =
        chat.get.flatMap { c =>
          val gen = leaveGen.incrementAndGet()
          leaving.set(Some(SessionLeave(id, c.pickerOpen))) *>
            chat.update(adoptView(_, id, waiting)) *>
            commit(hist, lastHref, BeardPath.sessionHref(id), WebviewMsg.ResumeSession(id), bridge) *>
            (ZIO.sleep(LeaveMs.millis) *>
              ZIO.when(leaveGen.get() == gen)(leaving.set(None))).forkDaemon.unit
        }

      def showPicker(c: ChatModel, leave: Option[SessionLeave]): Boolean =
        leave match
          case Some(s) => s.fromPicker
          case None    => c.pickerOpen

      def showWelcome(c: ChatModel, leave: Option[SessionLeave]): Boolean =
        !showPicker(c, leave) && ChatModel.listed(c).nonEmpty &&
          (ChatModel.isHome(c) || leave.exists(!_.fromPicker))

      def stageIdle(leave: Option[SessionLeave]): Boolean =
        leave.isEmpty

      def pickSlash(name: String): UIO[Unit] =
        if SessionCommands.isNew(name) then draft.set("") *> startNew
        else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then draft.set("") *> openPicker
        else if SessionCommands.isModel(name) then draft.set("") *> openMenu.set(Some(OpenMenu.Model))
        else if SessionCommands.isRename(name) then draft.set("/rename ")
        else if SessionCommands.isDelete(name) then draft.set("") *> chat.get.flatMap(c => armDelete(c.sessionId))
        else
          draft.set(s"/$name ") *>
            ZIO.succeed(bridge.post(WebviewMsg.SlashPick(name)))

      def applyRename(id: String, op: RenameOp): UIO[Unit] =
        if id.isEmpty then chat.update(_.copy(error = Some("No session to rename")))
        else
          val nextTitle = op match
            case RenameOp.Manual(t) => t
            case RenameOp.Auto      => ""
          chat.update { m =>
            val titled =
              op match
                case RenameOp.Manual(t) => t
                case RenameOp.Auto      => m.title
            m.copy(
              title = if id == m.sessionId then titled else m.title,
              sessions = m.sessions.map { r =>
                op match
                  case RenameOp.Manual(t) if r.id == id => r.copy(title = t)
                  case _                                => r
              },
              error = None,
            )
          } *> ZIO.succeed(bridge.post(WebviewMsg.RenameSession(id, nextTitle, auto = op == RenameOp.Auto)))

      def armDelete(id: String): UIO[Unit] =
        if id.isEmpty then chat.update(_.copy(error = Some("No session to delete")))
        else pendingDelete.set(Some(id))

      def cancelDelete: UIO[Unit] = pendingDelete.set(None)

      def confirmDelete: UIO[Unit] =
        pendingDelete.get.flatMap {
          case None     => ZIO.unit
          case Some(id) =>
            pendingDelete.set(None) *>
              chat.get.flatMap { c =>
                if c.sessionId == id then
                  leaving.set(None) *>
                    chat.update(adoptView(_, "", waiting)) *>
                    commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.DeleteSession(id), bridge)
                else
                  chat.update(m => m.copy(sessions = m.sessions.filterNot(_.id == id))) *>
                    ZIO.succeed(bridge.post(WebviewMsg.DeleteSession(id)))
              }
        }

      def toggleDelete(id: String): UIO[Unit] =
        pendingDelete.get.flatMap {
          case Some(armed) if armed == id => confirmDelete
          case _                          => pendingDelete.set(Some(id))
        }

      def openPicker: UIO[Unit] =
        leaving.set(None) *>
          pickerQuery.set("") *>
          openMenu.set(None) *>
          chat.update(ChatModel.thaw) *>
          ZIO.succeed(bridge.post(WebviewMsg.OpenSessionPicker))

      def closePicker: UIO[Unit] =
        pickerQuery.set("") *>
          ZIO.succeed(bridge.post(WebviewMsg.CloseSessionPicker))

      def pickMention(file: MentionFile): UIO[Unit] =
        val chip = PromptChip(file.path, file.absPath, source = "mention")
        draft.update { d =>
          d.replaceFirst("(?:^|\\s)@[^\\s]*$", " ").trim
        } *>
          dismissed.set(false) *>
          mentionIdx.set(None) *>
          chat.update(m => m.copy(chips = PromptChip.upsert(m.chips, chip))) *>
          ZIO.succeed(bridge.post(WebviewMsg.MentionPick(file.path, file.absPath))) *>
          ZIO.succeed(bridge.post(WebviewMsg.MentionQuery("")))
      end pickMention

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
        Ev.onKeyDown(onCardKey),
        renderToolbar(bridge, chat, openMenu, openPicker, startNew),
        E.div(
          Stage,
          when(Squawk.zipWith(chat, leaving)(showPicker))(
            renderPicker(
              chat,
              pickerQuery,
              leaving,
              pendingDelete,
              s => pickerQuery.set(s),
              closePicker,
              openSession,
              toggleDelete,
            )
          ),
          when(Squawk.zipWith(chat, leaving)((c, l) => stageIdle(l) && !c.pickerOpen && ChatModel.isLoading(c)))(
            E.div(
              Empty,
              TestId("session-loading"),
              E.h1(Title, chat.map(_.title)),
              E.p(Copy, "Loading session..."),
            )
          ),
          when(Squawk.zipWith(chat, leaving)((c, l) => stageIdle(l) && !c.pickerOpen && ChatModel.isEmptySession(c)))(
            E.div(
              Empty,
              TestId("session-empty"),
              E.h1(Title, chat.map(_.title)),
              E.p(Copy, "Ask Grok anything."),
            )
          ),
          when(
            Squawk.zipWith(chat, leaving)((c, l) =>
              !showPicker(c, l) && ChatModel.isHome(c) && ChatModel.listed(c).isEmpty && stageIdle(l)
            )
          )(
            E.div(
              Empty,
              heroLogo(logoSrc),
              E.h1(Title, chat.map(_.title)),
              E.p(Copy, "Ask Grok anything."),
            )
          ),
          when(Squawk.zipWith(chat, leaving)(showWelcome))(
            E.div(
              WelcomePane,
              E.div(
                EmptyHero,
                heroLogo(logoSrc),
                E.h1(Title, chat.map(_.title)),
                E.p(Copy, "Ask Grok anything."),
              ),
              E.div(
                WelcomeList,
                TestId("welcome-sessions"),
                E.p(Copy, "Recent sessions"),
                forEach(
                  Squawk.zipWith(chat, Squawk.zipWith(leaving, pendingDelete)(Tuple2.apply)) { (c, pack) =>
                    val (l, armed) = pack
                    val fadeId     = l.map(_.id)
                    ChatModel
                      .listed(c)
                      .take(SessionIndex.WelcomeLimit)
                      .map(r => (r, fadeId.contains(r.id), armed.contains(r.id)))
                  }
                )(p => s"${p._1.id}-${p._2}-${p._3}") { pair =>
                  sessionButton(pair._1, openSession, pair._2, pair._3, toggleDelete)
                },
              ),
            )
          ),
          when(
            Squawk.zipWith(chat, leaving)((c, l) =>
              stageIdle(l) && !c.pickerOpen && (c.turns.nonEmpty || c.queue.nonEmpty)
            )
          )(
            E.div(
              Transcript,
              TestId("transcript"),
              Lifecycle.onMountScoped[ascent.dom.Element, Any](TranscriptScroll.bind),
              forEachSignal(chat.map(_.turns))(_.id) { (id, _, turn) =>
                renderTurn(bridge, id, turn)
              },
              forEach(chat.map(_.queue))(_.id) { item =>
                renderQueued(item)
              },
            )
          ),
        ),
        renderCards(bridge, chat),
        when(pendingDelete.map(_.nonEmpty))(
          E.div(
            Cards,
            forEach(pendingDelete.map(_.toList))(identity) { id =>
              E.div(
                Card,
                TestId("delete-confirm"),
                E.h3("Delete this session?"),
                E.p(
                  Copy,
                  chat.map { c =>
                    val name = c.sessions.find(_.id == id).map(SessionIndex.displayTitle).getOrElse("This session")
                    s"$name will be removed from disk. This cannot be undone."
                  },
                ),
                E.button(
                  Send,
                  TestId("delete-yes"),
                  Ev.onClick(_ => confirmDelete),
                  "Delete",
                ),
                E.button(
                  CardBtn,
                  TestId("delete-no"),
                  Ev.onClick(_ => cancelDelete),
                  "Cancel",
                ),
              )
            },
          )
        ),
        renderDiff(bridge, chat),
        renderChanges(bridge, chat, changesOpen, changesOpen.update(!_)),
        when(chat.map(_.error.nonEmpty))(
          E.div(
            Toast,
            TestId("status"),
            chat.map(_.error.getOrElse("")),
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
        when(openMenu.map(_.contains(OpenMenu.Model)))(
          E.div(
            Popover,
            TestId("model-menu"),
            forEach(chat.map(_.models))(_.modelId) { model =>
              E.button(
                MenuItem,
                TestId(s"model-${model.modelId}"),
                A.title(model.description.getOrElse(model.modelId)),
                Ev.onClick(_ =>
                  openMenu.set(None) *>
                    chat.update(_.copy(modelId = model.modelId, error = None)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetModel(model.modelId)))
                ),
                model.name,
              )
            },
          )
        ),
        when(openMenu.map(_.contains(OpenMenu.Settings)))(
          E.div(
            PopoverEnd,
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
            ComposerMenu,
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
            ComposerMenu,
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
        renderComposer(
          bridge,
          chat,
          nowMs,
          draft,
          mentionIdx,
          slashShown,
          mentionShown,
          onDraft,
          sendDraft,
          dropChip,
          pickMention,
          pickSlash,
        ),
      )
    end for
  end component

  private def renderToolbar(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      openMenu: ascent.Source[Option[OpenMenu]],
      openPicker: UIO[Unit],
      startNew: UIO[Unit],
  ): ascent.ast.UI[Any] =
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
      when(chat.map(_.models.nonEmpty))(
        E.button(
          Chip,
          TestId("model"),
          A.title("Switch model"),
          Ev.onClick(_ =>
            openMenu.update {
              case Some(OpenMenu.Model) => None
              case _                    => Some(OpenMenu.Model)
            }
          ),
          chat.map(c => ModelOption.label(c.modelId, c.models)),
        )
      ),
      E.button(
        SessionChip,
        TestId("sessions"),
        A.title("Resume a previous session"),
        Ev.onClick(_ => openPicker),
        chat.map(sessionLabel),
      ),
      E.div(ToolbarGrow),
      E.button(
        Chip,
        TestId("new-session"),
        A.title("Start a new session"),
        Ev.onClick(_ => startNew),
        "New",
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
    )

  private def renderComposer(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      nowMs: ascent.Source[Long],
      draft: ascent.Source[String],
      mentionIdx: ascent.Source[Option[Int]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      onDraft: String => UIO[Unit],
      sendDraft: UIO[Unit],
      dropChip: PromptChip => UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      Composer,
      renderActivityStrip(chat, nowMs),
      renderChipRow(chat, dropChip),
      renderDraft(chat, draft, mentionIdx, slashShown, mentionShown, onDraft, sendDraft, pickMention, pickSlash),
      renderComposerBar(bridge, chat, sendDraft),
    )

  private def renderActivityStrip(chat: ascent.Source[ChatModel], nowMs: ascent.Source[Long]): ascent.ast.UI[Any] =
    forEach(Squawk.zipWith(chat, nowMs)((c, n) => TurnActivity.of(c, n).toList))(a =>
      s"${a.kind}-${a.label}-${a.elapsedMs / 1000}"
    ) { a =>
      renderActivity(a)
    }

  private def renderChipRow(chat: ascent.Source[ChatModel], dropChip: PromptChip => UIO[Unit]): ascent.ast.UI[Any] =
    when(chat.map(_.chips.nonEmpty))(
      E.div(
        ChipRow,
        TestId("chips"),
        forEach(chat.map(_.chips))(PromptChip.key) { chip =>
          val label = PromptChip.formatAtRef(chip)
          E.span(
            Chip,
            TestId(s"chip-${label.stripPrefix("@")}"),
            E.span(label),
            E.button(
              ChipRemove,
              TestId(s"chip-remove-${label.stripPrefix("@")}"),
              A.`type`("button"),
              A.title(s"Remove $label from chat"),
              Ev.onClick(_ => dropChip(chip)),
              "×",
            ),
          )
        },
      )
    )

  private def renderDraft(
      chat: ascent.Source[ChatModel],
      draft: ascent.Source[String],
      mentionIdx: ascent.Source[Option[Int]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      onDraft: String => UIO[Unit],
      sendDraft: UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
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
      Ev.onKeyDown(e => onDraftKey(e, chat, mentionIdx, slashShown, mentionShown, sendDraft, pickMention, pickSlash)),
    )

  private def renderComposerBar(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      sendDraft: UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      ComposerBar,
      when(chat.map(_.occupancy.exists(_.size > 0)))(
        occupancyEl(chat)
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
    )

  private def onDraftKey(
      e: ascent.dom.KeyboardEvent,
      chat: ascent.Source[ChatModel],
      mentionIdx: ascent.Source[Option[Int]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      sendDraft: UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
  ): UIO[Unit] =
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
        if mentions.nonEmpty && (key == "ArrowDown" || key == "ArrowUp") then
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
  end onDraftKey

  private def renderActivity(a: TurnActivity): ascent.ast.UI[Any] =
    val tone = a.kind match
      case ActivityKind.Think   => ActivityThink
      case ActivityKind.Edit    => ActivityEdit
      case ActivityKind.Read    => ActivityRead
      case ActivityKind.Execute => ActivityRun
      case ActivityKind.Search  => ActivitySearch
      case ActivityKind.Delete  => ActivityDelete
      case ActivityKind.Move    => ActivityMove
      case ActivityKind.Wait    => ActivityWait
      case ActivityKind.Other   => ActivityOther
    E.div(
      ActivityRow,
      TestId("activity"),
      E.span(
        ActivityIcon,
        tone,
        A.title(a.label),
        E.span(SpinGlyph, "⋅"),
        E.span(SpinGlyph, ":"),
        E.span(SpinGlyph, "⸬"),
        E.span(SpinGlyph, "⁙"),
      ),
      E.span(a.label),
      TurnActivity.timerLabel(a.elapsedMs).fold(E.span())(t => E.span(TestId("activity-timer"), t)),
    )
  end renderActivity

  private def heroLogo(logoSrc: Option[String]): ascent.ast.UI[Any] =
    val src = logoSrc.filter(_.nonEmpty).getOrElse("/logo.png")
    E.img(Logo, TestId("hero-logo"), A.src(src), A.alt("Grok's Beard"))

  private def renderQueued(item: QueuedPrompt): ascent.ast.UI[Any] =
    E.div(
      UserMsg,
      TestId(s"queue-${item.id}"),
      E.div(SessionMetaLine, "Queued"),
      QueuedPrompt.display(item),
    )

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
      Cards,
      TestId("cards"),
      forEach(chat.map(_.permission.toList))(_.requestId) { card =>
        val choices = card.options.zipWithIndex.map { (opt, idx) =>
          val skin = if idx == 0 then Send else CardBtn
          E.button(
            skin,
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
                CardBtn,
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

  private def renderChanges(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      changesOpen: ascent.Source[Boolean],
      toggleChanges: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(chat.map(_.changes.exists(_.files.nonEmpty)))(
      E.div(
        ChangesPane,
        TestId("changes"),
        E.button(
          ChangesHead,
          TestId("changes-toggle"),
          A.`type`("button"),
          A.title(changesOpen.map(open => if open then "Hide file list" else "Show file list")),
          Ev.onClick(_ => toggleChanges),
          E.strong("Grok Changes"),
          E.span(
            SessionMetaLine,
            chat.map { c =>
              c.changes.fold("") { s =>
                val n     = s.files.size
                val files = if n == 1 then "1 file" else s"$n files"
                s"${ChangeSet.formatStats(s.additions, s.deletions)} · $files"
              }
            },
          ),
          E.span(changesOpen.map(open => if open then "Hide" else "Show")),
        ),
        when(changesOpen)(
          E.div(
            ChangesList,
            TestId("changes-files"),
            forEach(chat.map(_.changes.toList.flatMap(s => ChangeSet.groupByTurn(s.files))))(g =>
              s"${g._1}-${g._3.map(_.path).mkString(",")}"
            ) { group =>
              val (turnId, title, files) = group
              val turnKey                = if turnId.nonEmpty then turnId else "changes"
              val (add, del)             = files.foldLeft((0, 0)) { case ((a, d), f) =>
                (a + f.additions, d + f.deletions)
              }
              E.div(
                ChangesTurn,
                TestId(s"change-turn-$turnKey"),
                E.div(
                  FileRow,
                  E.strong(title),
                  statsEl(add, del),
                  E.button(
                    Chip,
                    TestId(s"change-keep-all-$turnKey"),
                    Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.KeepTurn(turnId)))),
                    "Keep all",
                  ),
                  E.button(
                    Chip,
                    TestId(s"change-undo-all-$turnKey"),
                    Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.UndoTurn(turnId)))),
                    "Undo all",
                  ),
                ),
                Arg.ArgsArg(
                  files.map { file =>
                    val region = if file.wholeFile then "" else " region"
                    val reason = file.undoDisabled.fold("")(r => s" · $r")
                    Arg.ChildArg(
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
                    )
                  }
                ),
              )
            },
          )
        ),
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

  private def sessionLabel(c: ChatModel): String =
    val fromRow   = c.sessions.find(_.id == c.sessionId).map(SessionIndex.displayTitle)
    val fromTitle =
      Option(c.title).filter(t =>
        t.nonEmpty && t != "Grok's Beard" && t != "Untitled session" && !SessionIndex.isOpaqueId(t, c.sessionId)
      )
    val named = fromRow.filter(_ != "Untitled session").orElse(fromTitle)
    named
      .filter(_.nonEmpty)
      .getOrElse(
        if ChatModel.isLoading(c) || c.inSession then "This session"
        else if c.turns.isEmpty then "Resume"
        else "This session"
      )
  end sessionLabel

  private def sessionSubline(row: SessionRow): String =
    val shown = SessionIndex.displayTitle(row)
    val turn  = row.lastTurn.map(_.trim).filter(t => t.nonEmpty && t != shown)
    val sum   = row.summary.map(_.trim).filter(t => t.nonEmpty && t != shown && !turn.contains(t))
    List(turn.orElse(sum), row.modelId).flatten.mkString(" · ")

  private def sessionButton(
      row: SessionRow,
      openSession: String => UIO[Unit],
      fade: Boolean,
      armed: Boolean,
      onDelete: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    val extra: Seq[Arg[Any]] =
      if fade then Seq(SessionLeaving, Attr.StaticAttr("data-leaving", AttrValue.Str("true")))
      else Seq.empty
    E.div(
      (
        Seq[Arg[Any]](
          SessionRowEl,
          E.button(
            SessionItem,
            TestId(s"session-${row.id}"),
            A.title(row.id),
            Ev.onClick(_ => openSession(row.id)),
            E.span(SessionTitle, SessionIndex.displayTitle(row)),
            E.span(SessionMetaLine, sessionSubline(row)),
          ),
          E.button(
            Chip,
            TestId(s"session-delete-${row.id}"),
            A.title(if armed then "Press y to delete" else "Delete this session"),
            Ev.onClick(_ => onDelete(row.id)),
            if armed then "Confirm" else "Delete",
          ),
        ) ++ extra
      )*
    )
  end sessionButton

  private def renderPicker(
      chat: ascent.Source[ChatModel],
      query: ascent.Source[String],
      leaving: ascent.Source[Option[SessionLeave]],
      pendingDelete: ascent.Source[Option[String]],
      onQuery: String => UIO[Unit],
      close: UIO[Unit],
      openSession: String => UIO[Unit],
      onDelete: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    val shown =
      Squawk.zipWith(chat, Squawk.zipWith(query, Squawk.zipWith(leaving, pendingDelete)(Tuple2.apply))(Tuple2.apply)) {
        (c, pack) =>
          val (q, rest)      = pack
          val (leave, armed) = rest
          val fadeId         = leave.map(_.id)
          SessionIndex.filter(ChatModel.listed(c), q).map(r => (r, fadeId.contains(r.id), armed.contains(r.id)))
      }
    E.div(
      Picker,
      TestId("session-picker"),
      E.div(
        PickerHead,
        E.span("Resume session"),
        E.button(
          Chip,
          TestId("picker-close"),
          Ev.onClick(_ => close),
          "Close",
        ),
      ),
      when(chat.map(_.locked.nonEmpty))(
        E.p(Copy, TestId("session-locked"), chat.map(_.locked.getOrElse("")))
      ),
      E.input(
        Filter,
        TestId("session-filter"),
        A.`type`("search"),
        A.value(query),
        A.placeholder("Filter by title"),
        Events.onInput(e => onQuery(e.targetValue.getOrElse(""))),
      ),
      E.div(
        PickerList,
        forEach(shown)(p => s"${p._1.id}-${p._2}-${p._3}") { pair =>
          sessionButton(pair._1, openSession, pair._2, pair._3, onDelete)
        },
      ),
    )
  end renderPicker

  private def occupancyEl(chat: ascent.Source[ChatModel]): ascent.ast.UI[Any] =
    forEach(chat.map(_.occupancy.toList))(o => s"${o.used}/${o.size}") { o =>
      val pct  = Occupancy.percent(o.used, o.size)
      val fill =
        Occupancy.tone(o.used, o.size) match
          case "hot"  => OccupancyFillHot
          case "warn" => OccupancyFillWarn
          case _      => OccupancyFill
      E.span(
        OccupancyMeter,
        TestId("occupancy"),
        A.title(s"Context used this session: ${Occupancy.label(o.used, o.size)}"),
        E.span(
          OccupancyTrack,
          E.span(fill, Attr.StaticAttr("style", AttrValue.Str(s"width:${pct}%;height:100%;display:block"))),
        ),
        E.span(OccupancyCopy, Occupancy.label(o.used, o.size)),
      )
    }
end ChatApp
