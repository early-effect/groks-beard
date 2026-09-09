package groksbeard.ui

import ascent.*
import ascent.ast.Attr
import ascent.css.Color
import ascent.css.StateAttr
import ascent.css.Styles.*
import ascent.domtypes.AttrValue
import ascent.dsl.*
import ascent.dsl.Arg
import groksbeard.core.*
import groksbeard.facade.{Browser, MicConstraints, SpeechRec, SpeechRecInstance}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.scalajs.js
import zio.*
import zio.stream.ZStream

enum OpenMenu:
  case Mode, Settings, Model, Effort

object OpenMenu:
  given Eq[OpenMenu] = Eq.derived

final case class SessionLeave(id: SessionId, fromPicker: Boolean) derives Eq

enum Scene:
  case Empty, Slash, Mentions, Settings, Transcript, Permission, Plan, Question, Elicit, Changes, Resume, Todos,
    Tasks, Palette, Mcps, Queue, SessionInfo, Context, Child, Cancel, Agents, PlanView, Workflows, Dashboard, Btw,
    Theme, Compact, Doctor, Voice, Images

object Scene:
  def from(name: String): Scene =
    name match
      case "slash"        => Scene.Slash
      case "mentions"     => Scene.Mentions
      case "settings"     => Scene.Settings
      case "transcript"   => Scene.Transcript
      case "permission"   => Scene.Permission
      case "plan"         => Scene.Plan
      case "question"     => Scene.Question
      case "elicit"       => Scene.Elicit
      case "changes"      => Scene.Changes
      case "resume"       => Scene.Resume
      case "todos"        => Scene.Todos
      case "tasks"        => Scene.Tasks
      case "palette"      => Scene.Palette
      case "mcps"         => Scene.Mcps
      case "queue"        => Scene.Queue
      case "session-info" => Scene.SessionInfo
      case "context"      => Scene.Context
      case "child"        => Scene.Child
      case "cancel"       => Scene.Cancel
      case "agents"       => Scene.Agents
      case "plan-view"    => Scene.PlanView
      case "workflows"    => Scene.Workflows
      case "dashboard"    => Scene.Dashboard
      case "btw"          => Scene.Btw
      case "theme"        => Scene.Theme
      case "compact"      => Scene.Compact
      case "doctor"       => Scene.Doctor
      case "voice"        => Scene.Voice
      case "images"       => Scene.Images
      case _              => Scene.Empty
end Scene

object ChatApp:
  given toolOutEq: Eq[Map[ToolCallId, String]] = (a, b) => a == b

  private enum VoiceEvt:
    case Heard(text: String)
    case Fail(msg: String)

  private def takeFlag(flag: ascent.Source[Boolean], act: => UIO[Unit]): UIO[Boolean] =
    flag.get.flatMap(on => if on then act.as(true) else ZIO.succeed(false))

  private def takeSome[A](src: ascent.Source[Option[A]], act: A => UIO[Unit]): UIO[Boolean] =
    src.get.flatMap:
      case Some(a) => act(a).as(true)
      case None    => ZIO.succeed(false)

  private def firstHit(steps: UIO[Boolean]*): UIO[Boolean] =
    ZIO.foldLeft(steps.toList)(false): (done, step) =>
      if done then ZIO.succeed(true) else step

  private def isMenuNav(key: String, shift: Boolean): Boolean =
    key == "ArrowUp" || key == "ArrowDown" || key == "Home" || key == "End" ||
      ((key == "Enter" || key == "Tab") && !shift)

  private val PaletteFilterSel = """[data-testid="palette-filter"]"""

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

  object CompactRules
      extends GlobalStyle(
        Selector("[data-compact='true']", fontSize.px(12)),
        Selector("[data-compact='true'] [data-testid='draft']", minHeight.px(52), padding.px(6)),
      )

  object ThemeChoice
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.flexStart,
        gap.px(2),
        width.pct(100),
        boxSizing.borderBox,
        textAlign.left,
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
        minWidth.px(0),
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

  object ChipGroup
      extends CssClass(
        display.flex,
        alignItems.center,
        flexShrink(0),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(4),
        backgroundColor(inputBg),
        overflow.hidden,
      )

  object ChipSeg
      extends CssClass(
        border.none,
        borderRadius.px(0),
        padding(2.px, 8.px),
        backgroundColor(Color.transparent),
        color(fg),
        fontSize.px(12),
        cursor.pointer,
      )

  object ChipSegSplit
      extends CssClass(
        borderLeft(Border.solid(1.px, widgetBorder)),
        color(muted),
      )

  object ChipSegOn
      extends CssClass(
        border.none,
        borderRadius.px(0),
        padding(2.px, 8.px),
        backgroundColor(orange),
        color(cream),
        fontSize.px(12),
        cursor.pointer,
      )

  object ChipSegRule extends CssClass(borderLeft(Border.solid(1.px, widgetBorder)))

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
        border.none,
        backgroundColor(Color.transparent),
        padding.zero,
        minHeight.px(32),
        fontFamily.inherit,
        textAlign.left,
        cursor.pointer,
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

  object PaletteScrim
      extends CssClass(
        position.absolute,
        top.px(0),
        right.px(0),
        bottom.px(0),
        left.px(0),
        zIndex(30),
        display.flex,
        justifyContent.center,
        alignItems.flexStart,
        padding(48.px, 16.px, 16.px, 16.px),
        boxSizing.borderBox,
        backgroundColor(Color.Keyword("rgba(0, 0, 0, 0.45)")),
      )

  object PalettePanel
      extends CssClass(
        width.pct(100),
        maxWidth.px(480),
        maxHeight.pct(80),
        display.flex,
        flexDirection.column,
        minHeight.px(0),
        backgroundColor(menuBg),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(8),
        overflow.hidden,
        boxSizing.borderBox,
      )

  object PaletteList
      extends CssClass(
        display.flex,
        flexDirection.column,
        flexGrow(1.0),
        minHeight.px(0),
        overflowY.auto,
        padding.px(4),
      )

  object PaletteItem
      extends CssClass(
        display.flex,
        alignItems.center,
        justifyContent.spaceBetween,
        gap.px(8),
        width.pct(100),
        boxSizing.borderBox,
        border.none,
        backgroundColor(Color.transparent),
        color(fg),
        textAlign.left,
        padding(8.px, 10.px),
        cursor.pointer,
        fontSize.px(13),
      )

  object PaletteItemOn
      extends CssClass(
        display.flex,
        alignItems.center,
        justifyContent.spaceBetween,
        gap.px(8),
        width.pct(100),
        boxSizing.borderBox,
        border.none,
        backgroundColor(orange),
        color(cream),
        textAlign.left,
        padding(8.px, 10.px),
        cursor.pointer,
        fontSize.px(13),
      )

  object PaletteHint
      extends CssClass(
        flexShrink(0),
        fontSize.px(11),
        color(muted),
      )

  object PaletteDesc
      extends CssClass(
        display.block,
        fontSize.px(11),
        color(muted),
      )

  object FactRowEl
      extends CssClass(
        display.flex,
        alignItems.baseline,
        justifyContent.spaceBetween,
        gap.px(12),
        width.pct(100),
        boxSizing.borderBox,
        border.none,
        backgroundColor(Color.transparent),
        color(fg),
        textAlign.left,
        padding(8.px, 10.px),
        cursor.pointer,
        fontSize.px(13),
        fontFamily.inherit,
      )

  object FactLabel
      extends CssClass(
        flexShrink(0),
        fontSize.px(11),
        color(muted),
      )

  object FactValue
      extends CssClass(
        minWidth.px(0),
        textAlign.right,
        overflowWrap.anywhere,
        userSelect.text,
      )

  object ContextTrack
      extends CssClass(
        width.pct(100),
        height.px(6),
        borderRadius.px(3),
        backgroundColor(widgetBorder),
        overflow.hidden,
        flexShrink(0),
        margin(4.px, 10.px, 8.px, 10.px),
        boxSizing.borderBox,
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
        overflowX.hidden,
        overflowAnchor.none,
        padding.px(12),
        gap.px(12),
        minHeight.px(0),
        minWidth.px(0),
      )

  object Turn
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(8),
        minWidth.px(0),
        maxWidth.pct(100),
      )

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

  object AgentMsg
      extends CssClass(
        display.flex,
        flexDirection.column,
        gap.px(10),
        fontSize.px(13),
        color(fg),
        overflowWrap.anywhere,
        minWidth.px(0),
        maxWidth.pct(100),
        Selector(" p", margin.zero),
        Selector(" h1", margin.zero, fontSize.px(18), fontWeight(600)),
        Selector(" h2", margin.zero, fontSize.px(15), fontWeight(600)),
        Selector(" h3", margin.zero, fontSize.px(13), fontWeight(600)),
        Selector(" ul", margin.zero, paddingLeft.px(18)),
        Selector(" ol", margin.zero, paddingLeft.px(18)),
        Selector(" li", margin.zero),
        Selector(
          " pre",
          margin.zero,
          padding.px(8),
          backgroundColor(inputBg),
          border(Border.solid(1.px, widgetBorder)),
          borderRadius.px(6),
          whiteSpace.pre,
          overflowX.auto,
          overflowWrap.normal,
          fontSize.px(12),
          alignSelf.start,
          maxWidth.pct(100),
          boxSizing.borderBox,
        ),
        Selector(
          " code",
          fontSize.px(12),
          backgroundColor(inputBg),
          border(Border.solid(1.px, widgetBorder)),
          borderRadius.px(3),
          padding(1.px, 5.px),
        ),
        Selector(
          " pre code",
          backgroundColor(Color.transparent),
          border.none,
          padding.zero,
          fontSize.px(12),
        ),
        Selector(
          " blockquote",
          margin.zero,
          padding(2.px, 0.px, 2.px, 10.px),
          borderLeft(Border.solid(2.px, widgetBorder)),
          color(muted),
        ),
        Selector(" table", borderCollapse.collapse, fontSize.px(12)),
        Selector(
          " th",
          textAlign.left,
          padding(4.px, 8.px),
          borderBottom(Border.solid(1.px, widgetBorder)),
          fontWeight(600),
          whiteSpace.nowrap,
        ),
        Selector(
          " td",
          textAlign.left,
          padding(4.px, 8.px),
          borderBottom(Border.solid(1.px, widgetBorder)),
          verticalAlign.top,
        ),
        Selector(
          " a",
          color(orange),
        ),
      )

  object ThoughtBody
      extends CssClass(
        whiteSpace.preWrap,
        overflowWrap.anywhere,
        margin.zero,
        maxWidth.pct(100),
      )

  object ThoughtBox
      extends CssClass(
        fontSize.px(12),
        color(muted),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(6),
        minWidth.px(0),
        maxWidth.pct(100),
        overflowX.hidden,
        boxSizing.borderBox,
        Selector(" summary", cursor.pointer, overflowWrap.anywhere),
        Selector(
          " pre",
          whiteSpace.preWrap,
          overflowWrap.anywhere,
          margin.zero,
          maxWidth.pct(100),
        ),
      )

  object ToolBox
      extends CssClass(
        fontSize.px(12),
        border(Border.solid(1.px, widgetBorder)),
        borderRadius.px(6),
        padding.px(6),
        minWidth.px(0),
        maxWidth.pct(100),
        overflowX.hidden,
        boxSizing.borderBox,
        Selector(" summary", cursor.pointer, overflowWrap.anywhere),
        Selector(" pre", whiteSpace.preWrap, overflowWrap.anywhere, margin.zero, maxWidth.pct(100)),
        Selector("[open] > summary > pre", display.none),
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
        flexWrap.wrap,
        alignItems.center,
        gap.px(8),
        fontSize.px(12),
        color(muted),
        marginBottom.px(8),
        MediaQuery(Media.prefersReducedMotion.reduce, animation.none.important),
        Selector(" pre", margin.zero, whiteSpace.nowrap, overflow.hidden, textOverflow.ellipsis, maxWidth.pct(100)),
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

  object TodoMark
      extends CssClass(
        flexShrink(0),
        color(muted),
        fontSize.px(12),
      )

  object TodoDoing
      extends CssClass(
        color(orange)
      )

  object TodoDone
      extends CssClass(
        color(muted),
        textDecoration.lineThrough,
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

  private def adoptView(c: ChatModel, id: SessionId, waiting: AtomicReference[Option[SessionId]]): ChatModel =
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
      scope        <- ZIO.scope
      chat         <- sq(PreviewScenes.seed(scene))
      draft        <- sq(initialDraft)
      dismissed    <- sq(false)
      mentionIdx   <- sq(Option.empty[Int])
      slashIdx     <- sq(if ComposerQuery.slashQuery(initialDraft).isDefined then Some(0) else None)
      openMenu     <- sq(initialMenu)
      menuIdx      <- sq(if initialMenu.isDefined then Some(0) else None)
      pickerQuery  <- sq("")
      changesOpen  <- sq(false)
      todosOpen    <- sq(scene == Scene.Todos)
      tasksOpen    <- sq(scene == Scene.Tasks)
      queueOpen    <- sq(scene == Scene.Queue)
      queueIdx     <- sq(if scene == Scene.Queue then Some(0) else None)
      paletteOpen  <- sq(scene == Scene.Palette)
      paletteQuery <- sq("")
      paletteIdx   <- sq(if scene == Scene.Palette then Some(0) else None)
      mcpsOpen     <- sq(scene == Scene.Mcps)
      sessionPane  <- sq(
        if scene == Scene.SessionInfo then Some(SessionPane.Info)
        else if scene == Scene.Context then Some(SessionPane.Context)
        else None
      )
      leaving       <- sq(Option.empty[SessionLeave])
      pendingDelete <- sq(Option.empty[SessionId])
      lastIdleEsc   <- Ref.make(Option.empty[Long])
      lastCancelMs  <- Ref.make(Option.empty[Long])
      stash         <- sq(Option.empty[DraftStash])
      cancelOpen    <- sq(scene == Scene.Cancel)
      agentsOpen    <- sq(scene == Scene.Agents)
      planViewOpen  <- sq(scene == Scene.PlanView)
      workflowsOpen <- sq(scene == Scene.Workflows)
      dashboardOpen <- sq(scene == Scene.Dashboard)
      doctorOpen    <- sq(scene == Scene.Doctor)
      themeOpen     <- sq(scene == Scene.Theme)
      voiceOn       <- sq(scene == Scene.Voice)
      voiceRec  = new AtomicReference[Option[SpeechRecInstance]](None)
      voicePush = new AtomicReference[ChatApp.VoiceEvt => Unit](_ => ())
      childDraft     <- sq("")
      childQueue     <- sq(false)
      agentsTab      <- sq(if scene == Scene.Agents then "agents" else "agents")
      doctorFix      <- sq(false)
      restoreCode    <- sq(false)
      themePreview   <- sq(Option.empty[String])
      questionDraft  <- sq(QuestionDraft.empty)
      historyBrowse  <- sq(Option.empty[Int])
      historyPickIdx <- sq(Option.empty[Int])
      nowMs          <- wallMs.flatMap(sq(_))
      toolOut        <- sq(Map.empty[ToolCallId, String])(using ChatApp.toolOutEq)
      lastHref       <- Ref.make("")
      bound          <- Promise.make[Nothing, Unit]
      waiting  = new AtomicReference(Option.empty[SessionId])
      leaveGen = new AtomicInteger(0)
      clockFib <- ZStream
        .tick(1.second)
        .mapZIO(_ => wallMs.flatMap(nowMs.set))
        .runDrain
        .forkScoped
      burstFib <- FrameBurst(
        ZStream
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
            outputBuffer = 256,
          )
      )
        .mapZIO { batch =>
          wallMs.flatMap { now =>
            ZIO.foreachDiscard(batch) {
              case HostMsg.Copied(_, Some(text)) => writeClipboard(text)
              case HostMsg.ToggleTodos           => todosOpen.update(!_)
              case HostMsg.ToggleTasks           => tasksOpen.update(!_)
              case HostMsg.ToggleQueue           => queueOpen.update(!_)
              case HostMsg.OpenPalette           =>
                mcpsOpen.set(false) *> sessionPane.set(None) *> paletteQuery.set("") *> paletteIdx.set(Some(0)) *>
                  paletteOpen.set(true) *> ZIO.succeed(Dom.focusFirst(ChatApp.PaletteFilterSel))
              case HostMsg.OpenMcps =>
                paletteOpen.set(false) *> sessionPane.set(None) *> mcpsOpen.set(true)
              case _: HostMsg.PlanView =>
                planViewOpen.set(true)
              case _: HostMsg.Agents =>
                agentsOpen.set(true)
              case _: HostMsg.Workflows =>
                workflowsOpen.set(true)
              case _: HostMsg.Dashboard =>
                dashboardOpen.set(true)
              case _: HostMsg.DoctorReport =>
                doctorOpen.set(true)
              case HostMsg.UiPrefs(theme, _, _) =>
                ZIO.succeed(ChatApp.paintTheme(theme))
              case _: HostMsg.Btw =>
                ZIO.unit
              case HostMsg.ToolCall(_, row) =>
                toolOut.update { m =>
                  m.updated(row.id, m.getOrElse(row.id, row.output.getOrElse("")))
                }
              case HostMsg.ToolChunk(_, id, text, snap) =>
                toolOut.update(m => m.updated(id, ToolOutput.pull(m.getOrElse(id, ""), text, snap)))
              case HostMsg.ClearTranscript | _: HostMsg.Rewound =>
                toolOut.set(Map.empty)
              case HostMsg.Transcript(turns) =>
                toolOut.set(turns.flatMap(_.tools).map(t => t.id -> t.output.getOrElse("")).toMap)
              case HostMsg.Error(message, Some(Wire.Decode)) =>
                ZIO.succeed(groksbeard.facade.Browser.console.error(message)).unit
              case HostMsg.Ready =>
                waiting.get() match
                  case Some(id) if id.nonEmpty => ZIO.succeed(bridge.post(WebviewMsg.ResumeSession(id)))
                  case _                       => ZIO.unit
              case _ => ZIO.unit
            } *> chat.get.flatMap { before =>
              val next     = batch.foldLeft(before)((m, msg) => ChatModel.applyMsg(m, msg, now))
              val autoTodo = !Todos.isLive(before.todos) && Todos.isLive(next.todos)
              val hideTodo = (Todos.isLive(before.todos) && !Todos.isLive(next.todos)) ||
                (before.todos.nonEmpty && next.todos.isEmpty)
              val autoTask = !Tasks.isLive(before.tasks) && Tasks.isLive(next.tasks)
              val hideTask = (Tasks.isLive(before.tasks) && !Tasks.isLive(next.tasks)) ||
                (before.tasks.nonEmpty && next.tasks.isEmpty)
              val autoQ = before.queue.isEmpty && next.queue.nonEmpty
              chat.update(_ => next) *>
                ZIO.when(autoTodo)(todosOpen.set(true)).unit *>
                ZIO.when(hideTodo)(todosOpen.set(false)).unit *>
                ZIO.when(autoTask)(tasksOpen.set(true)).unit *>
                ZIO.when(hideTask)(tasksOpen.set(false)).unit *>
                ZIO.when(autoQ)(queueOpen.set(true) *> queueIdx.set(Some((next.queue.size - 1).max(0)))).unit
            }
          }
        }
        .runDrain
        .forkScoped
      voiceFib <- ZStream
        .asyncScoped[Any, Nothing, ChatApp.VoiceEvt] { emit =>
          ZIO.succeed {
            voicePush.set(evt => emit(ZIO.succeed(Chunk.single(evt))))
          }
        }
        .mapZIO {
          case ChatApp.VoiceEvt.Heard(text) => draft.update(d => VoiceCapture.append(d, text))
          case ChatApp.VoiceEvt.Fail(msg)   =>
            ZIO.succeed(ChatApp.stopRec(voiceRec)) *> voiceOn.set(false) *>
              chat.update(_.copy(error = Some(msg)))
        }
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
                    chat.update(adoptView(_, SessionId.empty, waiting)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.NewSession))
            )
        }
      }
      _ <- ZIO.addFinalizer(locSub.cancel)
    yield

      def stopHost: UIO[Unit] =
        ZIO.succeed(ChatApp.stopRec(voiceRec)) *>
          clockFib.interrupt.unit *> burstFib.interrupt.unit *> voiceFib.interrupt.unit *> locSub.cancel

      def setVoice(on: Boolean): UIO[Unit] =
        ZIO.succeed(ChatApp.stopRec(voiceRec)) *> (
          if !on then voiceOn.set(false)
          else
            SpeechRec.create() match
              case None =>
                voiceOn.set(false) *> chat.update(_.copy(error = Some(VoiceCapture.NoDevice)))
              case Some(rec) =>
                val started =
                  try
                    ChatApp.probeMic()
                    rec.continuous = true
                    rec.interimResults = false
                    rec.lang = "en-US"
                    rec.onresult = (e: groksbeard.facade.SpeechRecognitionEvent) =>
                      val spoken = SpeechRec.finalTranscript(e)
                      if spoken.nonEmpty then voicePush.get()(ChatApp.VoiceEvt.Heard(spoken))
                    rec.onerror = (e: groksbeard.facade.SpeechRecognitionErrorEvent) =>
                      val code = Option(e.error).getOrElse("")
                      if code == "not-allowed" || code == "audio-capture" || code == "service-not-allowed" then
                        voicePush.get()(ChatApp.VoiceEvt.Fail(VoiceCapture.NoDevice))
                    rec.onend = () => ()
                    rec.start()
                    voiceRec.set(Some(rec))
                    true
                  catch case _: Throwable => false
                if started then voiceOn.set(true)
                else voiceOn.set(false) *> chat.update(_.copy(error = Some(VoiceCapture.NoDevice)))
        )

      val slashShown = Squawk.zipWith(draft, chat) { (d, c) =>
        if PromptHistory.query(d).isDefined then Nil
        else ComposerQuery.slashQuery(d).map(q => ComposerQuery.filterSlash(c.commands, q)).getOrElse(Nil)
      }
      val historyShown = Squawk.zipWith(draft, chat) { (d, c) =>
        PromptHistory.query(d).map(q => PromptHistory.filter(PromptHistory.entries(c), q)).getOrElse(Nil)
      }
      val mentionShown = Squawk.zipWith(draft, Squawk.zipWith(chat, dismissed)(Tuple2.apply)) { (d, pack) =>
        val (c, disc) = pack
        ComposerQuery.mentionChoices(d, c.mentionQuery, c.mentionFiles, disc)
      }
      val paletteShown = Squawk.zipWith(chat, paletteQuery) { (c, q) =>
        Palette.filter(Palette.rows(c.commands), q)
      }

      def runCopyExport(cmd: ClientCommand): UIO[Unit] =
        chat.get.flatMap { c =>
          val job =
            if SessionCommands.isCopy(cmd.name) then TranscriptCopy.copy(c.turns, cmd.args)
            else TranscriptCopy.conversationJob(c.turns, c.inSession, cmd.args)
          job match
            case Left(err) =>
              draft.set("") *> chat.update(_.copy(error = Some(err)))
            case Right(out) =>
              val conv = SessionCommands.isExport(cmd.name)
              draft.set("") *>
                (if out.path.isEmpty then writeClipboard(out.text) else ZIO.unit) *>
                ZIO.succeed(
                  bridge.post(WebviewMsg.CopyOut(out.text, out.path, backup = SessionCommands.isCopy(cmd.name), conv))
                )
          end match
        }

      def sendDraft: UIO[Unit] =
        draft.get.flatMap { text =>
          chat.get.flatMap { c =>
            val trimmed = text.trim
            SessionCommands.intercept(trimmed) match
              case Some(cmd) if SessionCommands.isCopy(cmd.name) || SessionCommands.isExport(cmd.name) =>
                runCopyExport(cmd)
              case Some(cmd) if SessionCommands.isNew(cmd.name) =>
                draft.set("") *>
                  leaving.set(None) *>
                  chat.update(adoptView(_, SessionId.empty, waiting)) *>
                  commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.NewSession, bridge)
              case Some(cmd) if SessionCommands.isResume(cmd.name) || SessionCommands.isHome(cmd.name) =>
                draft.set("") *> openPicker
              case Some(cmd) if SessionCommands.isModel(cmd.name) && cmd.args.isEmpty =>
                draft.set("") *> showMenu(OpenMenu.Model)
              case Some(cmd) if SessionCommands.isModel(cmd.name) =>
                applyModel(cmd.args, c)
              case Some(cmd) if SessionCommands.isEffort(cmd.name) && cmd.args.isEmpty =>
                openEffort
              case Some(cmd) if SessionCommands.isEffort(cmd.name) =>
                applyEffort(cmd.args, c)
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
              case Some(cmd) if SessionCommands.isRewind(cmd.name) =>
                draft.set("") *>
                  (if ChatModel.turnIsRunning(c) then
                     chat.update(_.copy(error = Some("Stop the turn before rewinding.")))
                   else if cmd.args.isEmpty then openRewindPicker
                   else
                     cmd.args.trim.toIntOption match
                       case Some(i) => ZIO.succeed(bridge.post(WebviewMsg.RewindTo(i)))
                       case None    => chat.update(_.copy(error = Some("Usage: /rewind"))))
              case Some(cmd) if SessionCommands.isMcps(cmd.name) =>
                draft.set("") *> openMcps
              case Some(cmd) if SessionCommands.isSessionInfo(cmd.name) =>
                draft.set("") *> openSessionPane(SessionPane.Info)
              case Some(cmd) if SessionCommands.isContext(cmd.name) =>
                draft.set("") *> openSessionPane(SessionPane.Context)
              case Some(cmd) if SessionCommands.isTasks(cmd.name) =>
                draft.set("") *> toggleTasks
              case Some(cmd) if SessionCommands.isLoop(cmd.name) =>
                draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.Send(trimmed)))
              case Some(cmd) if SessionCommands.isFork(cmd.name) =>
                draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.Send(trimmed)))
              case Some(cmd) if SessionCommands.isViewPlan(cmd.name) =>
                draft.set("") *> planViewOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.ViewPlan))
              case Some(cmd) if SessionCommands.isBtw(cmd.name) =>
                draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.Btw(cmd.args)))
              case Some(cmd) if SessionCommands.isPersonas(cmd.name) =>
                draft.set("") *> agentsTab.set("personas") *> agentsOpen.set(true) *>
                  ZIO.succeed(bridge.post(WebviewMsg.OpenAgents))
              case Some(cmd) if SessionCommands.isConfigAgents(cmd.name) =>
                draft.set("") *> agentsTab.set("agents") *> agentsOpen.set(true) *>
                  ZIO.succeed(bridge.post(WebviewMsg.OpenAgents))
              case Some(cmd) if SessionCommands.isDashboard(cmd.name) =>
                draft.set("") *> dashboardOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenDashboard))
              case Some(cmd) if SessionCommands.isTheme(cmd.name) =>
                draft.set("") *>
                  (if cmd.args.isEmpty then themeOpen.set(true)
                   else ZIO.succeed(bridge.post(WebviewMsg.SetTheme(cmd.args))))
              case Some(cmd) if SessionCommands.isCompact(cmd.name) =>
                draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.ToggleCompact))
              case Some(cmd) if SessionCommands.isFullscreen(cmd.name) =>
                draft.set("") *> chat.update(_.copy(compact = false)) *>
                  ZIO.succeed(bridge.post(WebviewMsg.PersistConfig("ui", "compact_mode", "false")))
              case Some(cmd) if SessionCommands.isVim(cmd.name) =>
                draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.ToggleVim))
              case Some(cmd) if SessionCommands.isDoctor(cmd.name) =>
                draft.set("") *> doctorFix.set(cmd.args.trim == "fix") *> doctorOpen.set(true) *>
                  ZIO.succeed(bridge.post(WebviewMsg.OpenDoctor))
              case Some(cmd) if SessionCommands.isVoice(cmd.name) =>
                draft.set("") *> setVoice(true)
              case Some(cmd) if SessionCommands.isAlwaysApprove(cmd.name) =>
                draft.set("") *>
                  ZIO.succeed(
                    bridge.post(
                      WebviewMsg.SetMode(
                        if c.modeId == ModeId.AlwaysApprove then ModeId.Normal else ModeId.AlwaysApprove
                      )
                    )
                  )
              case Some(cmd) if SessionCommands.isAuto(cmd.name) =>
                draft.set("") *>
                  ZIO.succeed(
                    bridge.post(WebviewMsg.SetMode(if c.modeId == ModeId.Auto then ModeId.Normal else ModeId.Auto))
                  )
              case Some(cmd) if SessionCommands.isWorkflowRuns(cmd.name, cmd.args) =>
                draft.set("") *> workflowsOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenWorkflows))
              case Some(cmd) if SessionCommands.isHistory(cmd.name) =>
                val list = PromptHistory.filter(PromptHistory.entries(c), cmd.args)
                historyPickIdx.get.flatMap { idx =>
                  val pick = idx.flatMap(list.lift).orElse(list.headOption)
                  historyBrowse.set(None) *> historyPickIdx.set(None) *> pick.fold(draft.set(""))(draft.set)
                }
              case _ if trimmed.isEmpty && c.chips.isEmpty =>
                if ChatModel.turnIsRunning(c) && c.queue.nonEmpty then sendQueuedNow(c.queue.head.id)
                else ZIO.unit
              case _ =>
                val msg =
                  if ChatModel.turnIsRunning(c) then WebviewMsg.Queue(trimmed, c.images)
                  else WebviewMsg.Send(trimmed, c.images)
                ZIO.succeed(bridge.post(msg)) *> draft.set("") *> dismissed.set(false) *>
                  historyBrowse.set(None) *> historyPickIdx.set(None) *>
                  chat.update(_.copy(images = Nil)) *>
                  stash.get.flatMap:
                    case Some(s) if s.restoreAfterSend =>
                      draft.set(s.text) *>
                        chat.update(_.copy(chips = s.chips, images = s.images)) *>
                        stash.set(None)
                    case _ => ZIO.unit
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

      def applyQuestionPick(card: QuestionCard, optionId: String): UIO[Unit] =
        questionDraft.get.flatMap { held =>
          val d    = QuestionDraft.pick(card, held, optionId)
          val q    = QuestionDraft.current(card, d)
          val last = QuestionDraft.isLast(card, d)
          if q.exists(_.allowMultiple) then questionDraft.set(d)
          else if last then
            questionDraft.set(d) *>
              ZIO.succeed(bridge.post(WebviewMsg.QuestionSubmit(card.requestId, QuestionDraft.answers(card, d))))
          else questionDraft.set(QuestionDraft.next(card, d))
        }

      def typingInField(e: ascent.dom.KeyboardEvent): Boolean =
        e.target match
          case el: ascent.dom.Element =>
            val tag = el.tagName.toLowerCase
            tag == "textarea" || tag == "input"
          case _ => false

      def themeNav(key: String): UIO[Unit] =
        themePreview.get.flatMap { cur =>
          chat.get.flatMap { m =>
            val ids  = Theme.All.map(_.id)
            val now  = cur.getOrElse(Theme.canonicalize(m.theme))
            val i    = math.max(0, ids.indexOf(now))
            val next =
              key match
                case "ArrowDown" => ids((i + 1) % ids.size)
                case "ArrowUp"   => ids((i - 1 + ids.size) % ids.size)
                case "Home"      => ids.head
                case "End"       => ids.last
                case _           => now
            if key == "Enter" then
              themePreview.set(None) *> themeOpen.set(false) *>
                ZIO.succeed(bridge.post(WebviewMsg.SetTheme(next))) *>
                ZIO.succeed(ChatApp.paintTheme(next))
            else themePreview.set(Some(next)) *> ZIO.succeed(ChatApp.paintTheme(next))
          }
        }

      def applyVim(c: ChatModel, motion: VimNav.Motion): UIO[Unit] =
        val ids = c.turns.map(_.id)
        if ids.isEmpty then ZIO.unit
        else
          val cur =
            c.selectedTurn.flatMap(id => Option(ids.indexOf(id)).filter(_ >= 0)).getOrElse(ids.size - 1)
          val next = VimNav.step(cur, ids.size, motion)
          val sel  = ids.lift(next)
          motion match
            case VimNav.Motion.Yank =>
              val text = sel.flatMap(id => c.turns.find(_.id == id)).map(t => t.agent).getOrElse("")
              chat.update(_.copy(selectedTurn = sel)) *> writeClipboard(text)
            case VimNav.Motion.Fold =>
              chat.update(_.copy(selectedTurn = sel)) *>
                sel.map(id => ChatApp.toggleDetails(s"""[data-testid="thought-${id.value}"]""")).getOrElse(ZIO.unit)
            case VimNav.Motion.Collapse | VimNav.Motion.Expand =>
              chat.update(_.copy(selectedTurn = sel)) *>
                sel.map(id => ChatApp.toggleDetails(s"""[data-testid="turn-${id.value}"]""")).getOrElse(ZIO.unit)
            case _ =>
              chat.update(_.copy(selectedTurn = sel))
          end match
        end if
      end applyVim

      def ingestClipboard(e: ascent.dom.ClipboardEvent): UIO[Unit] =
        ChatApp.imageFiles(e.clipboardData) match
          case Nil   => ZIO.unit
          case files =>
            ZIO.succeed(e.preventDefault()) *> ZIO.foreachDiscard(files)(ingestImageFile)

      def ingestDrop(e: ascent.dom.DragEvent): UIO[Unit] =
        ChatApp.imageFiles(e.dataTransfer) match
          case Nil   => ZIO.unit
          case files =>
            ZIO.succeed(e.preventDefault()) *> ZIO.foreachDiscard(files)(ingestImageFile)

      def ingestImageFile(file: ascent.dom.File): UIO[Unit] =
        ChatApp.readImage(file: js.Any).flatMap {
          case None                     => ZIO.unit
          case Some((mime, data, name)) =>
            chat.get.flatMap { c =>
              val chip = ImageAttach.mint(c.images.size + 1, mime, data, name)
              chat.update(_.copy(images = c.images :+ chip)) *>
                ZIO.succeed(bridge.post(WebviewMsg.AddImage(mime, data, name)))
            }
        }

      def dropImage(id: String): UIO[Unit] =
        chat.update(m => m.copy(images = m.images.filterNot(_.id == id))) *>
          ZIO.succeed(bridge.post(WebviewMsg.RemoveImage(id)))

      def onPaletteKey(e: ascent.dom.KeyboardEvent): UIO[Boolean] =
        paletteOpen.get.flatMap {
          case false => ZIO.succeed(false)
          case true  =>
            val key                            = e.key
            val ctrlOrMeta                     = e.ctrlKey || e.metaKey
            def go(z: UIO[Unit]): UIO[Boolean] =
              e.preventDefault()
              e.stopPropagation()
              z.as(true)
            val inFilter = e.target match
              case el: ascent.dom.Element =>
                Option(el.getAttribute("data-testid")).contains("palette-filter")
              case _ => false
            if key == "Escape" || (ctrlOrMeta && !e.shiftKey && (key == "p" || key == "P")) then go(closePalette)
            else if key == "ArrowDown" || key == "ArrowUp" || key == "Home" || key == "End" then
              paletteShown.get.flatMap { list =>
                paletteIdx.get.flatMap { cur =>
                  go(paletteIdx.set(ComposerQuery.moveIndex(cur, key, list.size)))
                }
              }
            else if (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
              paletteShown.get.flatMap { list =>
                paletteIdx.get.flatMap { cur =>
                  list.lift(cur.getOrElse(0)) match
                    case Some(row) => go(pickPalette(row))
                    case None      => ZIO.succeed(false)
                }
              }
            else if inFilter then ZIO.succeed(false)
            else if key == "Backspace" && !ctrlOrMeta then
              go(paletteQuery.update(_.dropRight(1)) *> paletteIdx.set(Some(0)))
            else if !ctrlOrMeta && !e.altKey && key.length == 1 then
              go(paletteQuery.update(_ + key) *> paletteIdx.set(Some(0)))
            else ZIO.succeed(false)
            end if
        }

      def onSessionPaneKey(e: ascent.dom.KeyboardEvent): UIO[Boolean] =
        sessionPane.get.flatMap {
          case None       => ZIO.succeed(false)
          case Some(pane) =>
            val key                            = e.key
            val ctrlOrMeta                     = e.ctrlKey || e.metaKey
            def go(z: UIO[Unit]): UIO[Boolean] =
              e.preventDefault()
              e.stopPropagation()
              z.as(true)
            if ctrlOrMeta || e.altKey then ZIO.succeed(false)
            else if key == "c" || key == "C" then go(copySessionId)
            else if key == "y" || key == "Y" then go(copySessionBlock(pane))
            else if key == "Tab" && !e.shiftKey then go(sessionPane.set(Some(SessionPane.other(pane))))
            else ZIO.succeed(false)
        }

      def toastEsc: UIO[Unit] =
        wallMs.flatMap: now =>
          lastCancelMs.set(Some(now)) *> lastIdleEsc.set(None) *>
            chat.update(_.copy(error = Some(CancelTurn.EscHint)))

      def closeChild: UIO[Unit] =
        chat.update(_.copy(attachedChild = None)) *> ZIO.succeed(bridge.post(WebviewMsg.DetachChild))

      def closeBtw: UIO[Unit] =
        chat.update(_.copy(btw = None, btwDone = false))

      def onEscape(c: ChatModel): UIO[Unit] =
        draft.get.flatMap: text =>
          mentionShown.get.flatMap: mentions =>
            firstHit(
              takeSome(openMenu, _ => hideMenu),
              takeFlag(cancelOpen, cancelOpen.set(false)),
              takeFlag(
                themeOpen,
                chat.get.flatMap { m =>
                  themeOpen.set(false) *> themePreview.set(None) *> ZIO.succeed(ChatApp.paintTheme(m.theme))
                },
              ),
              takeFlag(agentsOpen, agentsOpen.set(false)),
              takeFlag(planViewOpen, planViewOpen.set(false) *> chat.update(_.copy(planView = None))),
              takeFlag(workflowsOpen, workflowsOpen.set(false)),
              takeFlag(dashboardOpen, dashboardOpen.set(false)),
              takeFlag(doctorOpen, doctorOpen.set(false)),
              takeFlag(voiceOn, setVoice(false)),
              ZIO
                .succeed(c.attachedChild.nonEmpty)
                .flatMap(on => if on then closeChild.as(true) else ZIO.succeed(false)),
              ZIO.succeed(c.btw.nonEmpty).flatMap(on => if on then closeBtw.as(true) else ZIO.succeed(false)),
              takeFlag(paletteOpen, closePalette),
              takeFlag(mcpsOpen, closeMcps),
              takeSome(sessionPane, _ => closeSessionPane),
              takeSome(pendingDelete, _ => cancelDelete),
              ZIO
                .succeed(c.rewindConfirm.nonEmpty)
                .flatMap(on => if on then cancelRewind.as(true) else ZIO.succeed(false)),
              ZIO
                .succeed(c.rewind.nonEmpty)
                .flatMap(on => if on then closeRewindPicker.as(true) else ZIO.succeed(false)),
              ZIO
                .succeed(c.forkAsk.nonEmpty)
                .flatMap(on => if on then chat.update(_.copy(forkAsk = None)).as(true) else ZIO.succeed(false)),
              ZIO
                .succeed(mentions.nonEmpty)
                .flatMap(on =>
                  if on then (dismissed.set(true) *> mentionIdx.set(None)).as(true) else ZIO.succeed(false)
                ),
              ZIO
                .succeed(PromptHistory.query(text).isDefined)
                .flatMap: on =>
                  if on then (draft.set("") *> historyPickIdx.set(None) *> historyBrowse.set(None)).as(true)
                  else ZIO.succeed(false),
              takeSome(historyBrowse, _ => historyBrowse.set(None)),
              ZIO.succeed(c.pickerOpen).flatMap(on => if on then closePicker.as(true) else ZIO.succeed(false)),
              ZIO
                .succeed(c.permission.isDefined)
                .flatMap(on => if on then parkPermission.as(true) else ZIO.succeed(false)),
              ZIO
                .succeed(c.question.isDefined)
                .flatMap: on =>
                  if on then ZIO.succeed(bridge.post(WebviewMsg.QuestionDismiss(c.question.get.requestId))).as(true)
                  else ZIO.succeed(false),
              ZIO
                .succeed(c.plan.isDefined)
                .flatMap: on =>
                  if on then
                    ZIO
                      .succeed(bridge.post(WebviewMsg.PlanVerdict(c.plan.get.requestId, PlanOutcome.Abandoned)))
                      .as(true)
                  else ZIO.succeed(false),
              ZIO
                .succeed(c.elicit.isDefined)
                .flatMap: on =>
                  if on then ZIO.succeed(bridge.post(WebviewMsg.ElicitDecline(c.elicit.get.requestId))).as(true)
                  else ZIO.succeed(false),
              takeFlag(queueOpen, queueOpen.set(false)),
              takeFlag(todosOpen, todosOpen.set(false)),
              takeFlag(tasksOpen, tasksOpen.set(false)),
            ).flatMap: hit =>
              if hit then ZIO.unit
              else if ChatModel.turnIsRunning(c) then toastEsc
              else idleEsc(c, text)

      def onCtrlC(c: ChatModel): UIO[Unit] =
        draft.get.flatMap: text =>
          if text.trim.nonEmpty || c.chips.nonEmpty || c.images.nonEmpty then
            draft.set("") *> chat.update(_.copy(chips = Nil, images = Nil, error = None))
          else if ChatModel.turnIsRunning(c) then requestCancel(c)
          else ZIO.unit

      def onCardKey(e: ascent.dom.KeyboardEvent): UIO[Unit] =
        onPaletteKey(e).flatMap {
          case true  => ZIO.unit
          case false =>
            onSessionPaneKey(e).flatMap {
              case true  => ZIO.unit
              case false =>
                onQueueKey(e).flatMap {
                  case true  => ZIO.unit
                  case false => handleShellKey(e)
                }
            }
        }

      def handleShellKey(e: ascent.dom.KeyboardEvent): UIO[Unit] =
        val key                            = e.key
        val ctrlOrMeta                     = e.ctrlKey || e.metaKey
        def steal(z: UIO[Unit]): UIO[Unit] =
          e.preventDefault()
          e.stopPropagation()
          z
        chat.get.flatMap { c =>
          if (e.ctrlKey || e.metaKey) && !e.shiftKey && (key == "p" || key == "P") then
            if typingInField(e) then ZIO.unit
            else steal(togglePalette)
          else if e.ctrlKey && !e.metaKey && !e.shiftKey && (key == "t" || key == "T") && !c.pickerOpen then
            if typingInField(e) then ZIO.unit
            else steal(toggleTodos)
          else if e.ctrlKey && !e.metaKey && !e.shiftKey && (key == "g" || key == "G") && !c.pickerOpen then
            if typingInField(e) then ZIO.unit
            else steal(toggleTasks)
          else if e.ctrlKey && !e.metaKey && !e.shiftKey && key == "4" && !c.pickerOpen then
            if typingInField(e) then ZIO.unit
            else steal(toggleQueue)
          else if key == "Escape" then steal(onEscape(c))
          else if ctrlOrMeta && !e.shiftKey && (key == "c" || key == "C") then steal(onCtrlC(c))
          else if (e.ctrlKey || e.altKey) && !e.shiftKey && (key == "s" || key == "S") then steal(chordStash)
          else if e.ctrlKey && !e.metaKey && !e.shiftKey && (key == "o" || key == "O") then
            steal(
              ZIO.succeed(
                bridge.post(
                  WebviewMsg.SetMode(
                    if c.modeId == ModeId.AlwaysApprove then ModeId.Normal else ModeId.AlwaysApprove
                  )
                )
              )
            )
          else if e.ctrlKey && !e.shiftKey && key == "\\" then
            steal(dashboardOpen.update(!_) *> ZIO.succeed(bridge.post(WebviewMsg.OpenDashboard)))
          else if (e.ctrlKey && !e.shiftKey && key == " ") || key == "F8" then
            steal(voiceOn.get.flatMap(on => setVoice(!on)))
          else
            themeOpen.get.flatMap { theme =>
              if theme && ChatApp.isThemeNav(key) then steal(themeNav(key))
              else
                cancelOpen.get.flatMap: open =>
                  if open && CancelTurn.pick(key).nonEmpty then steal(applyCancelChoice(CancelTurn.pick(key).get))
                  else if !typingInField(e) && c.vim && VimNav.motion(key, e.shiftKey).nonEmpty then
                    steal(applyVim(c, VimNav.motion(key, e.shiftKey).get))
                  else
                    (if typingInField(e) then ZIO.succeed(false) else onMenuKey(e)).flatMap {
                      case true  => ZIO.unit
                      case false =>
                        if e.shiftKey && key == "Tab" && !ctrlOrMeta then
                          steal {
                            if c.question.isDefined || c.permission.isDefined then ZIO.unit
                            else hideMenu *> ZIO.succeed(bridge.post(WebviewMsg.CycleMode))
                          }
                        else if e.shiftKey && (key == "X" || key == "x") && c.question.isDefined then
                          steal(ZIO.succeed(bridge.post(WebviewMsg.QuestionDismiss(c.question.get.requestId))))
                        else
                          pendingDelete.get.flatMap {
                            case Some(_) if key == "y" || key == "Y"                         => steal(confirmDelete)
                            case Some(_) if key == "n" || key == "N"                         => steal(cancelDelete)
                            case _ if c.rewindConfirm.nonEmpty && (key == "y" || key == "Y") =>
                              steal(confirmRewind)
                            case _ if c.rewindConfirm.nonEmpty && (key == "n" || key == "N") =>
                              steal(cancelRewind)
                            case _ =>
                              c.permission.flatMap(p => ComposerQuery.permissionOption(key, p.options)) match
                                case Some(opt) =>
                                  steal(
                                    ZIO.succeed(
                                      bridge.post(WebviewMsg.PermissionChoice(c.permission.get.requestId, opt.optionId))
                                    )
                                  )
                                case None =>
                                  c.question match
                                    case Some(_) if typingInField(e) => ZIO.unit
                                    case Some(card)                  =>
                                      questionDraft.get.flatMap { held =>
                                        val d = QuestionDraft.align(card, held)
                                        QuestionDraft.navKey(key) match
                                          case Some("prev") => steal(questionDraft.set(QuestionDraft.prev(card, d)))
                                          case Some("next") => steal(questionDraft.set(QuestionDraft.next(card, d)))
                                          case _            =>
                                            QuestionDraft
                                              .current(card, d)
                                              .flatMap(q => QuestionDraft.optionKey(key, q)) match
                                              case Some(oid) => steal(applyQuestionPick(card, oid))
                                              case None      => ZIO.unit
                                        end match
                                      }
                                    case None =>
                                      c.plan match
                                        case Some(card) if !typingInField(e) && (key == "a" || key == "A") =>
                                          steal(
                                            ZIO.succeed(
                                              bridge.post(WebviewMsg.PlanVerdict(card.requestId, PlanOutcome.Approved))
                                            )
                                          )
                                        case Some(card) if !typingInField(e) && (key == "q" || key == "Q") =>
                                          steal(
                                            ZIO.succeed(
                                              bridge.post(WebviewMsg.PlanVerdict(card.requestId, PlanOutcome.Abandoned))
                                            )
                                          )
                                        case _ => ZIO.unit
                          }
                    }
            }
        }
      end handleShellKey

      def startNew: UIO[Unit] =
        leaving.set(None) *>
          historyBrowse.set(None) *>
          historyPickIdx.set(None) *>
          chat.update(adoptView(_, SessionId.empty, waiting)) *>
          commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.NewSession, bridge)

      def openSession(id: SessionId): UIO[Unit] =
        chat.get.flatMap { c =>
          restoreCode.get.flatMap { restore =>
            val gen = leaveGen.incrementAndGet()
            leaving.set(Some(SessionLeave(id, c.pickerOpen))) *>
              chat.update(adoptView(_, id, waiting)) *>
              commit(hist, lastHref, BeardPath.sessionHref(id), WebviewMsg.ResumeSession(id, restore), bridge) *>
              (ZIO.sleep(LeaveMs.millis) *>
                ZIO.when(leaveGen.get() == gen)(leaving.set(None))).forkIn(scope).unit
          }
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

      def pickHistory(text: String): UIO[Unit] =
        historyBrowse.set(None) *> historyPickIdx.set(None) *> draft.set(text)

      def levelsOf(c: ChatModel): List[EffortLevel] =
        Effort.of(c.models.find(_.modelId == c.modelId))

      def menuIds(c: ChatModel, menu: OpenMenu): List[String] =
        menu match
          case OpenMenu.Mode     => c.modes.map(_.id.value)
          case OpenMenu.Model    => c.models.map(_.modelId.value)
          case OpenMenu.Effort   => levelsOf(c).map(_.value)
          case OpenMenu.Settings => List("useCtrlEnterToSend", "includeActiveFileByDefault")

      def menuStart(c: ChatModel, menu: OpenMenu): Int =
        val ids = menuIds(c, menu)
        val cur = menu match
          case OpenMenu.Mode     => c.modeId.value
          case OpenMenu.Model    => c.modelId.value
          case OpenMenu.Effort   => c.effort
          case OpenMenu.Settings => ""
        val i = ids.indexOf(cur)
        if i >= 0 then i else 0

      def hideMenu: UIO[Unit] =
        openMenu.set(None) *> menuIdx.set(None)

      def showMenu(menu: OpenMenu): UIO[Unit] =
        chat.get.flatMap { c =>
          openMenu.set(Some(menu)) *>
            menuIdx.set(Some(menuStart(c, menu))) *>
            (if menu == OpenMenu.Settings then ZIO.succeed(bridge.post(WebviewMsg.OpenSettings)) else ZIO.unit)
        }

      def toggleTodos: UIO[Unit] =
        todosOpen.update(!_)

      def toggleTasks: UIO[Unit] =
        tasksOpen.update(!_)

      def stopTask(id: TaskId): UIO[Unit] =
        ZIO.succeed(bridge.post(WebviewMsg.StopTask(id)))

      def toggleQueue: UIO[Unit] =
        chat.get.flatMap { c =>
          if c.queue.isEmpty then ZIO.unit
          else queueOpen.update(!_)
        }

      def sendQueuedNow(id: QueueId): UIO[Unit] =
        chat.update(m => m.copy(queue = m.queue.filterNot(_.id == id))) *>
          queueIdx.set(Some(0)) *>
          ZIO.succeed(bridge.post(WebviewMsg.QueueSendNow(id)))

      def dropQueued(id: QueueId): UIO[Unit] =
        chat.update { m =>
          val next = m.queue.filterNot(_.id == id)
          m.copy(queue = next)
        } *>
          chat.get.flatMap { m =>
            queueIdx.set(if m.queue.isEmpty then None else Some(0)) *>
              ZIO.when(m.queue.isEmpty)(queueOpen.set(false)).unit
          } *>
          ZIO.succeed(bridge.post(WebviewMsg.QueueDrop(id)))

      def editQueued(item: QueuedPrompt): UIO[Unit] =
        dropQueued(item.id) *> draft.set(QueuedPrompt.display(item))

      def onQueueKey(e: ascent.dom.KeyboardEvent): UIO[Boolean] =
        val key                            = e.key
        val ctrlOrMeta                     = e.ctrlKey || e.metaKey
        def go(z: UIO[Unit]): UIO[Boolean] =
          e.preventDefault()
          e.stopPropagation()
          z.as(true)
        draft.get.flatMap { text =>
          chat.get.flatMap { c =>
            if c.queue.isEmpty then ZIO.succeed(false)
            else if e.ctrlKey && !e.metaKey && !e.shiftKey && key == "4" then go(toggleQueue)
            else if text.nonEmpty then ZIO.succeed(false)
            else
              queueOpen.get.flatMap { open =>
                queueIdx.get.flatMap { idx =>
                  if key == "ArrowUp" && idx.isEmpty && c.chips.isEmpty then
                    go(queueOpen.set(true) *> queueIdx.set(Some(c.queue.size - 1)))
                  else if open && (key == "ArrowDown" || key == "ArrowUp" || key == "Home" || key == "End") then
                    go(queueIdx.set(ComposerQuery.moveIndex(idx.orElse(Some(0)), key, c.queue.size)))
                  else if open && (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
                    c.queue.lift(idx.getOrElse(0)) match
                      case Some(item) => go(sendQueuedNow(item.id))
                      case None       => ZIO.succeed(false)
                  else if open && !ctrlOrMeta && (key == "e" || key == "E") then
                    c.queue.lift(idx.getOrElse(0)) match
                      case Some(item) => go(editQueued(item))
                      case None       => ZIO.succeed(false)
                  else if open && (key == "Backspace" || key == "Delete") then
                    c.queue.lift(idx.getOrElse(0)) match
                      case Some(item) => go(dropQueued(item.id))
                      case None       => ZIO.succeed(false)
                  else ZIO.succeed(false)
                }
              }
          }
        }
      end onQueueKey

      def toggleMenu(menu: OpenMenu): UIO[Unit] =
        openMenu.get.flatMap {
          case Some(m) if m == menu => hideMenu
          case _                    => showMenu(menu)
        }

      def chooseMode(id: ModeId): UIO[Unit] =
        hideMenu *>
          chat.update(_.copy(modeId = id)) *>
          ZIO.succeed(bridge.post(WebviewMsg.SetMode(id)))

      def chooseModel(model: ModelOption): UIO[Unit] =
        chat.get.flatMap { c =>
          val allowed    = Effort.of(Some(model))
          val nextEffort =
            if allowed.exists(_.value == c.effort) then c.effort else Effort.defaultOf(Some(model))
          hideMenu *>
            chat.update(_.copy(modelId = model.modelId, effort = nextEffort, error = None)) *>
            ZIO.succeed(bridge.post(WebviewMsg.SetModel(model.modelId)))
        }

      def chooseEffort(level: EffortLevel): UIO[Unit] =
        hideMenu *>
          chat.update(_.copy(effort = level.value, error = None)) *>
          ZIO.succeed(bridge.post(WebviewMsg.SetEffort(level.value)))

      def chooseSetting(id: String): UIO[Unit] =
        chat.get.flatMap { c =>
          id match
            case "useCtrlEnterToSend" =>
              val next = !c.settings.useCtrlEnterToSend
              chat.update(_.copy(settings = c.settings.copy(useCtrlEnterToSend = next))) *>
                ZIO.succeed(bridge.post(WebviewMsg.SetSetting("useCtrlEnterToSend", next)))
            case "includeActiveFileByDefault" =>
              val next = !c.settings.includeActiveFileByDefault
              chat.update(_.copy(settings = c.settings.copy(includeActiveFileByDefault = next))) *>
                ZIO.succeed(bridge.post(WebviewMsg.SetSetting("includeActiveFileByDefault", next)))
            case _ => ZIO.unit
        }

      def pickMenuRow(menu: OpenMenu, id: String): UIO[Unit] =
        menu match
          case OpenMenu.Mode  => chooseMode(ModeId(id))
          case OpenMenu.Model =>
            chat.get.flatMap { c =>
              c.models.find(_.modelId == ModelId(id)).map(chooseModel).getOrElse(ZIO.unit)
            }
          case OpenMenu.Effort =>
            chat.get.flatMap { c =>
              levelsOf(c).find(_.value == id).map(chooseEffort).getOrElse(ZIO.unit)
            }
          case OpenMenu.Settings => chooseSetting(id)

      def onMenuKey(e: ascent.dom.KeyboardEvent): UIO[Boolean] =
        val key = e.key
        if !ChatApp.isMenuNav(key, e.shiftKey) then ZIO.succeed(false)
        else
          openMenu.get.flatMap {
            case None       => ZIO.succeed(false)
            case Some(menu) =>
              chat.get.flatMap { c =>
                val ids = menuIds(c, menu)
                if ids.isEmpty then ZIO.succeed(false)
                else if key == "Enter" || key == "Tab" then
                  e.preventDefault()
                  e.stopPropagation()
                  menuIdx.get.flatMap { idx =>
                    ids.lift(idx.getOrElse(0)) match
                      case None     => ZIO.succeed(true)
                      case Some(id) => pickMenuRow(menu, id).as(true)
                  }
                else
                  e.preventDefault()
                  e.stopPropagation()
                  menuIdx.update(i => ComposerQuery.moveIndex(i, key, ids.size)).as(true)
                end if
              }
          }
        end if
      end onMenuKey

      def openEffort: UIO[Unit] =
        chat.get.flatMap { c =>
          val allowed = levelsOf(c)
          if c.modelId.isEmpty then draft.set("") *> chat.update(_.copy(error = Some(Effort.NoModel)))
          else if allowed.isEmpty then draft.set("") *> chat.update(_.copy(error = Some(Effort.unknown("", Nil))))
          else draft.set("") *> showMenu(OpenMenu.Effort)
        }

      def applyEffort(raw: String, c: ChatModel): UIO[Unit] =
        val allowed = levelsOf(c)
        if c.modelId.isEmpty then draft.set("") *> chat.update(_.copy(error = Some(Effort.NoModel)))
        else
          Effort.pick(raw, allowed) match
            case Some(level) =>
              draft.set("") *>
                chat.update(_.copy(effort = level.value, error = None)) *>
                ZIO.succeed(bridge.post(WebviewMsg.SetEffort(level.value)))
            case None =>
              draft.set("") *> chat.update(_.copy(error = Some(Effort.unknown(raw, allowed))))
      end applyEffort

      def applyModel(args: String, c: ChatModel): UIO[Unit] =
        Effort.splitModelArgs(args, c.models) match
          case Left(err) =>
            draft.set("") *> chat.update(_.copy(error = Some(err)))
          case Right((m, e)) =>
            val allowed    = Effort.of(Some(m))
            val nextEffort =
              e.getOrElse(if allowed.exists(_.value == c.effort) then c.effort else Effort.defaultOf(Some(m)))
            draft.set("") *>
              chat.update(_.copy(modelId = m.modelId, effort = nextEffort, error = None)) *>
              ZIO.succeed(bridge.post(WebviewMsg.SetModel(m.modelId, e.getOrElse(""))))

      def closePalette: UIO[Unit] =
        paletteOpen.set(false) *> paletteQuery.set("") *> paletteIdx.set(None)

      def openPalette: UIO[Unit] =
        hideMenu *>
          mcpsOpen.set(false) *>
          sessionPane.set(None) *>
          paletteQuery.set("") *>
          paletteIdx.set(Some(0)) *>
          paletteOpen.set(true) *>
          ZIO.succeed(Dom.focusFirst(ChatApp.PaletteFilterSel))

      def togglePalette: UIO[Unit] =
        paletteOpen.get.flatMap {
          case true  => closePalette
          case false => openPalette
        }

      def closeMcps: UIO[Unit] = mcpsOpen.set(false)

      def openMcps: UIO[Unit] =
        hideMenu *>
          closePalette *>
          sessionPane.set(None) *>
          mcpsOpen.set(true) *>
          ZIO.succeed(bridge.post(WebviewMsg.ListMcps))

      def closeSessionPane: UIO[Unit] = sessionPane.set(None)

      def openSessionPane(pane: SessionPane): UIO[Unit] =
        hideMenu *>
          closePalette *>
          closeMcps *>
          sessionPane.set(Some(pane))

      def copyFact(text: String): UIO[Unit] =
        if text.isEmpty then ZIO.unit
        else
          writeClipboard(text) *>
            ZIO.succeed(bridge.post(WebviewMsg.CopyOut(text, None, backup = false, conversation = false)))

      def copySessionId: UIO[Unit] =
        chat.get.flatMap(c => copyFact(SessionFacts.sessionId(c).getOrElse("")))

      def copySessionBlock(pane: SessionPane): UIO[Unit] =
        chat.get.flatMap(c => copyFact(SessionFacts.block(pane, c)))

      def pickPalette(row: PaletteRow): UIO[Unit] =
        closePalette *> (
          row.kind match
            case PaletteKind.Mcps        => openMcps
            case PaletteKind.Todos       => toggleTodos
            case PaletteKind.Tasks       => toggleTasks
            case PaletteKind.Settings    => showMenu(OpenMenu.Settings)
            case PaletteKind.SessionInfo => openSessionPane(SessionPane.Info)
            case PaletteKind.Context     => openSessionPane(SessionPane.Context)
            case PaletteKind.Agents      => agentsOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenAgents))
            case PaletteKind.Dashboard => dashboardOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenDashboard))
            case PaletteKind.PlanView  => planViewOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.ViewPlan))
            case PaletteKind.Workflows => workflowsOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenWorkflows))
            case PaletteKind.Doctor    => doctorOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenDoctor))
            case PaletteKind.Theme     => themeOpen.set(true)
            case PaletteKind.Voice     => setVoice(true)
            case PaletteKind.Slash(name) => pickSlash(name)
        )

      def toggleMcp(name: String, enabled: Boolean): UIO[Unit] =
        ZIO.succeed(bridge.post(WebviewMsg.SetMcpEnabled(name, enabled)))

      def pickSlash(name: String): UIO[Unit] =
        if SessionCommands.isNew(name) then draft.set("") *> startNew
        else if SessionCommands.isResume(name) || SessionCommands.isHome(name) then draft.set("") *> openPicker
        else if SessionCommands.isModel(name) then draft.set("") *> showMenu(OpenMenu.Model)
        else if SessionCommands.isEffort(name) then openEffort
        else if SessionCommands.isRename(name) then draft.set("/rename ")
        else if SessionCommands.isDelete(name) then draft.set("") *> chat.get.flatMap(c => armDelete(c.sessionId))
        else if SessionCommands.isHistory(name) then draft.set("/history ") *> historyPickIdx.set(Some(0))
        else if SessionCommands.isCopy(name) then runCopyExport(ClientCommand("copy"))
        else if SessionCommands.isExport(name) then runCopyExport(ClientCommand("export"))
        else if SessionCommands.isRewind(name) then draft.set("") *> openRewindPicker
        else if SessionCommands.isMcps(name) then draft.set("") *> openMcps
        else if SessionCommands.isSessionInfo(name) then draft.set("") *> openSessionPane(SessionPane.Info)
        else if SessionCommands.isContext(name) then draft.set("") *> openSessionPane(SessionPane.Context)
        else if SessionCommands.isTasks(name) then draft.set("") *> toggleTasks
        else if SessionCommands.isLoop(name) then draft.set("/loop ")
        else if SessionCommands.isFork(name) then draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.Send("/fork")))
        else if SessionCommands.isViewPlan(name) then
          draft.set("") *> planViewOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.ViewPlan))
        else if SessionCommands.isBtw(name) then draft.set("/btw ")
        else if SessionCommands.isPersonas(name) then
          draft.set("") *> agentsTab.set("personas") *> agentsOpen.set(true) *>
            ZIO.succeed(bridge.post(WebviewMsg.OpenAgents))
        else if SessionCommands.isConfigAgents(name) then
          draft.set("") *> agentsTab.set("agents") *> agentsOpen.set(true) *>
            ZIO.succeed(bridge.post(WebviewMsg.OpenAgents))
        else if SessionCommands.isDashboard(name) then
          draft.set("") *> dashboardOpen.set(true) *> ZIO.succeed(bridge.post(WebviewMsg.OpenDashboard))
        else if SessionCommands.isTheme(name) then draft.set("") *> themeOpen.set(true)
        else if SessionCommands.isCompact(name) then draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.ToggleCompact))
        else if SessionCommands.isFullscreen(name) then
          draft.set("") *> chat.update(_.copy(compact = false)) *>
            ZIO.succeed(bridge.post(WebviewMsg.PersistConfig("ui", "compact_mode", "false")))
        else if SessionCommands.isVim(name) then draft.set("") *> ZIO.succeed(bridge.post(WebviewMsg.ToggleVim))
        else if SessionCommands.isDoctor(name) then
          draft.set("") *> doctorFix.set(false) *> doctorOpen.set(true) *>
            ZIO.succeed(bridge.post(WebviewMsg.OpenDoctor))
        else if SessionCommands.isVoice(name) then draft.set("") *> setVoice(true)
        else if SessionCommands.isAlwaysApprove(name) then
          chat.get.flatMap { c =>
            draft.set("") *>
              ZIO.succeed(
                bridge.post(
                  WebviewMsg.SetMode(if c.modeId == ModeId.AlwaysApprove then ModeId.Normal else ModeId.AlwaysApprove)
                )
              )
          }
        else
          draft.set(s"/$name ") *>
            ZIO.succeed(bridge.post(WebviewMsg.SlashPick(name)))

      def applyRename(id: SessionId, op: RenameOp): UIO[Unit] =
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

      def armDelete(id: SessionId): UIO[Unit] =
        if id.isEmpty then chat.update(_.copy(error = Some("No session to delete")))
        else pendingDelete.set(Some(id))

      def openRewindPicker: UIO[Unit] =
        chat.get.flatMap { c =>
          if ChatModel.turnIsRunning(c) then chat.update(_.copy(error = Some("Stop the turn before rewinding.")))
          else
            val local = Rewind.fromTurns(c.turns)
            if local.isEmpty then chat.update(_.copy(error = Some("Nothing to rewind")))
            else
              draft.set("") *>
                chat.update(_.copy(rewind = local, rewindConfirm = None, error = None, pickerOpen = false)) *>
                ZIO.succeed(bridge.post(WebviewMsg.OpenRewind))
        }

      def closeRewindPicker: UIO[Unit] =
        chat.update(_.copy(rewind = Nil, rewindConfirm = None)) *>
          ZIO.succeed(bridge.post(WebviewMsg.CloseRewind))

      def armRewind(point: RewindPoint): UIO[Unit] =
        chat.update(_.copy(rewindConfirm = Some(point)))

      def idleEsc(c: ChatModel, text: String): UIO[Unit] =
        wallMs.flatMap { now =>
          lastCancelMs.get.flatMap { cancelAt =>
            if cancelAt.exists(now - _ < 1000L) then ZIO.unit
            else if text.trim.nonEmpty || c.chips.nonEmpty || c.images.nonEmpty then
              lastIdleEsc.get.flatMap { prev =>
                if prev.exists(now - _ <= 800L) then
                  lastIdleEsc.set(None) *>
                    stash.set(DraftStash.take(text, c.chips, restoreAfterSend = false, c.images)) *>
                    draft.set("") *>
                    chat.update(_.copy(chips = Nil, images = Nil, error = Some(DraftStash.SavedToast)))
                else lastIdleEsc.set(Some(now)) *> chat.update(_.copy(error = Some(CancelTurn.ClearHint)))
              }
            else idleRewindEsc(c, text)
          }
        }

      def idleRewindEsc(c: ChatModel, text: String): UIO[Unit] =
        if Rewind.fromTurns(c.turns).isEmpty then lastIdleEsc.set(None)
        else
          wallMs.flatMap { now =>
            lastIdleEsc.get.flatMap { prev =>
              if prev.exists(now - _ <= 800L) then lastIdleEsc.set(None) *> openRewindPicker
              else lastIdleEsc.set(Some(now))
            }
          }

      def requestCancel(c: ChatModel): UIO[Unit] =
        if CancelTurn.needsPanel(c.tasks, None) then cancelOpen.set(true)
        else ZIO.succeed(bridge.post(WebviewMsg.Cancel))

      def applyCancelChoice(choice: CancelChoice): UIO[Unit] =
        val keep     = CancelTurn.keepChildren(choice)
        val remember =
          CancelTurn.remember(choice) match
            case Some(keepAlways) =>
              val value = if keepAlways then "always_continue" else "always_stop"
              ZIO.succeed(bridge.post(WebviewMsg.PersistConfig("ui", "cancel_subagents_on_turn_cancel", value)))
            case None => ZIO.unit
        remember *> cancelOpen.set(false) *> ZIO.succeed(
          bridge.post(WebviewMsg.CancelTurnChoice(choice.toString, keep))
        )
      end applyCancelChoice

      def chordStash: UIO[Unit] =
        draft.get.flatMap { text =>
          chat.get.flatMap { c =>
            stash.get.flatMap { held =>
              if DraftStash.isEmpty(text, c.chips, c.images) then
                held match
                  case None    => ZIO.unit
                  case Some(s) =>
                    draft.set(s.text) *>
                      chat.update(
                        _.copy(chips = s.chips, images = s.images, error = Some(DraftStash.RestoredToast))
                      ) *>
                      stash.set(None)
              else
                stash.set(DraftStash.take(text, c.chips, restoreAfterSend = true, c.images)) *>
                  draft.set("") *>
                  chat.update(_.copy(chips = Nil, images = Nil, error = Some(DraftStash.SavedToast)))
            }
          }
        }

      def cancelRewind: UIO[Unit] = chat.update(_.copy(rewindConfirm = None))

      def confirmRewind: UIO[Unit] =
        chat.get.flatMap { c =>
          c.rewindConfirm match
            case None    => ZIO.unit
            case Some(p) =>
              chat.update(_.copy(rewind = Nil, rewindConfirm = None)) *>
                ZIO.succeed(bridge.post(WebviewMsg.RewindTo(p.promptIndex)))
        }

      def cancelDelete: UIO[Unit] = pendingDelete.set(None)

      def confirmDelete: UIO[Unit] =
        pendingDelete.get.flatMap {
          case None     => ZIO.unit
          case Some(id) =>
            pendingDelete.set(None) *>
              chat.get.flatMap { c =>
                if c.sessionId == id then
                  leaving.set(None) *>
                    chat.update(adoptView(_, SessionId.empty, waiting)) *>
                    commit(hist, lastHref, BeardPath.Welcome, WebviewMsg.DeleteSession(id), bridge)
                else
                  chat.update(m => m.copy(sessions = m.sessions.filterNot(_.id == id))) *>
                    ZIO.succeed(bridge.post(WebviewMsg.DeleteSession(id)))
              }
        }

      def toggleDelete(id: SessionId): UIO[Unit] =
        pendingDelete.get.flatMap {
          case Some(armed) if armed == id => confirmDelete
          case _                          => pendingDelete.set(Some(id))
        }

      def openPicker: UIO[Unit] =
        leaving.set(None) *>
          pickerQuery.set("") *>
          hideMenu *>
          chat.update(ChatModel.thaw) *>
          ZIO.succeed(bridge.post(WebviewMsg.OpenSessionPicker))

      def closePicker: UIO[Unit] =
        pickerQuery.set("") *>
          ZIO.succeed(bridge.post(WebviewMsg.CloseSessionPicker))

      def pickMention(file: MentionFile): UIO[Unit] =
        val chip = PromptChip(file.path, file.absPath, source = ChipSource.Mention)
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
          hideMenu *>
          (if PromptHistory.query(text).isDefined then historyPickIdx.set(Some(0))
           else historyPickIdx.set(None)) *>
          (if ComposerQuery.slashQuery(text).isDefined then slashIdx.set(Some(0))
           else slashIdx.set(None)) *>
          (ComposerQuery.mentionQuery(text) match
            case None =>
              dismissed.set(false) *> mentionIdx.set(None) *>
                ZIO.succeed(bridge.post(WebviewMsg.MentionQuery("")))
            case Some(q) =>
              dismissed.set(false) *> mentionIdx.set(Some(0)) *>
                ZIO.succeed(bridge.post(WebviewMsg.MentionQuery(q))))

      val shownTheme =
        Squawk.zipWith(chat, themePreview) { (c, p) => p.getOrElse(Theme.canonicalize(c.theme)) }
      E.div(
        Shell,
        Page,
        CompactRules,
        StateAttr("data-theme", shownTheme).toAttr,
        StateAttr("data-compact", chat.map(c => if c.compact then "true" else "false")).toAttr,
        StateAttr("data-vim", chat.map(c => if c.vim then "true" else "false")).toAttr,
        Attr.ReactiveAttr("style", shownTheme.map(id => AttrValue.Str(ChatApp.themeInline(id)))),
        Ev.onKeyDown(onCardKey),
        Ev.onPaste(e => ingestClipboard(e)),
        Ev.onDrop(e => ingestDrop(e)),
        Ev.sync.onDragOver(e => e.preventDefault()),
        Lifecycle.onMountScoped[ascent.dom.Element, Any] { _ =>
          chat.get.flatMap(c => ZIO.succeed(ChatApp.paintTheme(c.theme))) *>
            ZIO.addFinalizer(stopHost).unit
        },
        Dom.onDocument[ascent.dom.Element, Any](Events.onKeyDown) { (_, ev) =>
          ev.keyboard.fold(ZIO.unit)(onCardKey)
        },
        renderToolbar(chat, toggleMenu, openPicker, startNew, stash),
        E.div(
          Stage,
          when(Squawk.zipWith(chat, leaving)(showPicker))(
            renderPicker(
              chat,
              pickerQuery,
              leaving,
              pendingDelete,
              restoreCode,
              s => pickerQuery.set(s),
              closePicker,
              openSession,
              toggleDelete,
              restoreCode.update(!_),
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
            Squawk.zipWith(chat, leaving)((c, l) => stageIdle(l) && !c.pickerOpen && c.turns.nonEmpty)
          )(
            E.div(
              Transcript,
              TestId("transcript"),
              Lifecycle.onMountScoped[ascent.dom.Element, Any](TranscriptScroll.bind),
              forEachSignal(chat.map(_.turns))(_.id.value) { (id, _, turn) =>
                renderTurn(
                  bridge,
                  id,
                  turn,
                  toolOut,
                  chat.map(_.selectedTurn.contains(TurnId(id))),
                  chat.update(_.copy(selectedTurn = Some(TurnId(id)))),
                  sid =>
                    chat.update(_.copy(attachedChild = Some(SessionId(sid)))) *>
                      ZIO.succeed(bridge.post(WebviewMsg.AttachChild(TaskId(sid)))),
                )
              },
            )
          ),
        ),
        renderCards(bridge, chat, questionDraft, applyQuestionPick),
        renderForkAsk(bridge, chat),
        renderRewind(chat, armRewind, confirmRewind, cancelRewind),
        when(pendingDelete.map(_.nonEmpty))(
          E.div(
            Cards,
            forEach(pendingDelete.map(_.toList))(_.value) { id =>
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
        renderTodos(chat, todosOpen, toggleTodos),
        renderTasks(chat, tasksOpen, toggleTasks, stopTask),
        renderQueue(chat, queueOpen, queueIdx, toggleQueue, sendQueuedNow, dropQueued, editQueued),
        renderChanges(bridge, chat, changesOpen, changesOpen.update(!_)),
        renderCancelTurn(cancelOpen, chat, applyCancelChoice),
        renderChildFrame(
          chat,
          childDraft,
          childQueue,
          closeChild,
          t =>
            val body = t.trim
            if body.isEmpty then ZIO.unit
            else
              childQueue.get.flatMap { queued =>
                childDraft.set("") *>
                  chat.get.flatMap: c =>
                    c.attachedChild.fold(ZIO.unit): sid =>
                      ZIO.succeed(bridge.post(WebviewMsg.SteerChild(TaskId(sid.value), body, queued)))
              }
          ,
          s => childDraft.set(s),
          childQueue.update(!_),
        ),
        renderAgents(agentsOpen, agentsTab, chat, agentsOpen.set(false), tab => agentsTab.set(tab)),
        renderPlanView(planViewOpen, chat, planViewOpen.set(false) *> chat.update(_.copy(planView = None))),
        renderWorkflows(workflowsOpen, chat, workflowsOpen.set(false)),
        renderDashboard(dashboardOpen, chat, dashboardOpen.set(false), openSession),
        renderDoctor(doctorOpen, doctorFix, chat, doctorOpen.set(false)),
        renderTheme(
          themeOpen,
          chat,
          themePreview,
          themeOpen.set(false) *> themePreview.set(None) *> chat.get.flatMap(m =>
            ZIO.succeed(ChatApp.paintTheme(m.theme))
          ),
          id =>
            themePreview.set(None) *> themeOpen.set(false) *>
              ZIO.succeed(bridge.post(WebviewMsg.SetTheme(id))) *> ZIO.succeed(ChatApp.paintTheme(id)),
        ),
        renderBtw(chat, closeBtw),
        renderVoice(voiceOn),
        renderImages(chat, dropImage),
        when(chat.map(_.error.nonEmpty))(
          E.div(
            Toast,
            TestId("status"),
            chat.map(_.error.getOrElse("")),
          )
        ),
        renderChromeMenus(chat, openMenu, menuIdx, chooseMode, chooseModel, chooseEffort, chooseSetting),
        when(paletteOpen)(
          renderPalette(
            paletteShown,
            paletteQuery,
            paletteIdx,
            closePalette,
            pickPalette,
            s => paletteQuery.set(s) *> paletteIdx.set(Some(0)),
            onPaletteKey,
          )
        ),
        when(mcpsOpen)(renderMcps(chat, closeMcps, toggleMcp)),
        when(sessionPane.map(_.nonEmpty))(
          renderSessionPane(chat, sessionPane, closeSessionPane, openSessionPane, copyFact)
        ),
        when(historyShown.map(_.nonEmpty))(
          E.ul(
            ComposerMenu,
            TestId("history"),
            A.role("listbox"),
            forEach(
              Squawk.zipWith(historyShown, historyPickIdx) { (rows, idx) =>
                rows.zipWithIndex.map { (text, i) => (text, i, idx.contains(i)) }
              }
            )(t => s"${t._2}-${t._1}") { t =>
              val (text, i, on) = t
              E.li(
                E.button(
                  if on then Send else MenuItem,
                  TestId(s"history-$i"),
                  Ev.onClick(_ => pickHistory(text)),
                  PromptHistory.label(text),
                )
              )
            },
          )
        ),
        when(slashShown.map(_.nonEmpty))(
          E.ul(
            ComposerMenu,
            TestId("slash"),
            A.role("listbox"),
            forEach(
              Squawk.zipWith(slashShown, slashIdx) { (cmds, idx) =>
                cmds.zipWithIndex.map { (cmd, i) => (cmd, i, idx.contains(i)) }
              }
            )(t => s"${t._1.name}-${t._3}") { t =>
              val (cmd, _, on) = t
              E.li(
                E.button(
                  if on then Send else MenuItem,
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
          slashIdx,
          openMenu,
          slashShown,
          mentionShown,
          historyShown,
          historyBrowse,
          historyPickIdx,
          onDraft,
          sendDraft,
          dropChip,
          pickMention,
          pickSlash,
          pickHistory,
          onMenuKey,
          toggleTodos,
          toggleTasks,
          togglePalette,
          openPalette,
          onPaletteKey,
          onSessionPaneKey,
          onQueueKey,
          openSessionPane(SessionPane.Context),
          requestCancel,
        ),
      )
    end for
  end component

  private def renderChromeMenus(
      chat: ascent.Source[ChatModel],
      openMenu: ascent.Source[Option[OpenMenu]],
      menuIdx: ascent.Source[Option[Int]],
      chooseMode: ModeId => UIO[Unit],
      chooseModel: ModelOption => UIO[Unit],
      chooseEffort: EffortLevel => UIO[Unit],
      chooseSetting: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      when(openMenu.map(_.contains(OpenMenu.Mode)))(
        E.div(
          Popover,
          TestId("mode-menu"),
          forEach(
            Squawk.zipWith(chat, menuIdx) { (c, idx) =>
              c.modes.zipWithIndex.map { (mode, i) => (mode, idx.contains(i)) }
            }
          )(t => s"${t._1.id}-${t._2}") { t =>
            val (mode, on) = t
            E.button(
              if on then Send else MenuItem,
              TestId(s"mode-${mode.id}"),
              A.title(ModeLabel.modeTip(mode.id)),
              Ev.onClick(_ => chooseMode(mode.id)),
              mode.name,
            )
          },
        )
      ),
      when(openMenu.map(_.contains(OpenMenu.Model)))(
        E.div(
          Popover,
          TestId("model-menu"),
          forEach(
            Squawk.zipWith(chat, menuIdx) { (c, idx) =>
              c.models.zipWithIndex.map { (model, i) => (model, idx.contains(i)) }
            }
          )(t => s"${t._1.modelId}-${t._2}") { t =>
            val (model, on) = t
            E.button(
              if on then Send else MenuItem,
              TestId(s"model-${model.modelId}"),
              A.title(model.description.getOrElse(model.modelId.value)),
              Ev.onClick(_ => chooseModel(model)),
              model.name,
            )
          },
        )
      ),
      when(openMenu.map(_.contains(OpenMenu.Effort)))(
        E.div(
          Popover,
          TestId("effort-menu"),
          forEach(
            Squawk.zipWith(chat, menuIdx) { (c, idx) =>
              Effort.of(c.models.find(_.modelId == c.modelId)).zipWithIndex.map { (level, i) =>
                (level, idx.contains(i))
              }
            }
          )(t => s"${t._1.value}-${t._2}") { t =>
            val (level, on) = t
            E.button(
              if on then Send else MenuItem,
              TestId(s"effort-${level.value}"),
              Ev.onClick(_ => chooseEffort(level)),
              level.label.getOrElse(level.value),
            )
          },
        )
      ),
      when(openMenu.map(_.contains(OpenMenu.Settings)))(
        E.div(
          PopoverEnd,
          TestId("settings-panel"),
          forEach(
            Squawk.zipWith(chat, menuIdx) { (c, idx) =>
              List(
                (
                  "ctrl-enter",
                  "useCtrlEnterToSend",
                  if c.settings.useCtrlEnterToSend then "Ctrl+Enter to send: on" else "Ctrl+Enter to send: off",
                  idx.contains(0),
                ),
                (
                  "active-file",
                  "includeActiveFileByDefault",
                  if c.settings.includeActiveFileByDefault then "Include active file: on"
                  else "Include active file: off",
                  idx.contains(1),
                ),
              )
            }
          )(t => s"${t._1}-${t._3}-${t._4}") { t =>
            val (testId, id, label, on) = t
            E.button(
              if on then Send else MenuItem,
              TestId(s"setting-$testId"),
              Ev.onClick(_ => chooseSetting(id)),
              label,
            )
          },
        )
      ),
    )

  private def renderToolbar(
      chat: ascent.Source[ChatModel],
      toggleMenu: OpenMenu => UIO[Unit],
      openPicker: UIO[Unit],
      startNew: UIO[Unit],
      stash: ascent.Source[Option[DraftStash]],
  ): ascent.ast.UI[Any] =
    E.div(
      Toolbar,
      E.button(
        Chip,
        TestId("mode"),
        A.title(chat.map(c => ModeLabel.modeTip(c.modeId))),
        Ev.onClick(_ => toggleMenu(OpenMenu.Mode)),
        chat.map(c => ModeLabel.modeLabel(c.modeId, c.modes)),
      ),
      when(chat.map(_.models.nonEmpty))(
        E.div(
          ChipGroup,
          TestId("model-group"),
          E.button(
            ChipSeg,
            TestId("model"),
            A.title("Switch model"),
            Ev.onClick(_ => toggleMenu(OpenMenu.Model)),
            chat.map(c => ModelOption.label(c.modelId, c.models)),
          ),
          when(chat.map(c => Effort.of(c.models.find(_.modelId == c.modelId)).nonEmpty))(
            E.button(
              ChipSeg,
              ChipSegSplit,
              TestId("effort"),
              A.title("Set reasoning effort"),
              Ev.onClick(_ => toggleMenu(OpenMenu.Effort)),
              chat.map { c =>
                if c.effort.nonEmpty then c.effort
                else
                  val d = Effort.defaultOf(c.models.find(_.modelId == c.modelId))
                  if d.nonEmpty then d else "effort"
              },
            )
          ),
        )
      ),
      E.button(
        SessionChip,
        TestId("sessions"),
        A.title("Resume a previous session"),
        Ev.onClick(_ => openPicker),
        chat.map(sessionLabel),
        when(stash.map(_.nonEmpty))(
          E.span(SessionMetaLine, TestId("stash"), DraftStash.Caption)
        ),
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
        Ev.onClick(_ => toggleMenu(OpenMenu.Settings)),
        "Settings",
      ),
    )

  private def renderComposer(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      nowMs: ascent.Source[Long],
      draft: ascent.Source[String],
      mentionIdx: ascent.Source[Option[Int]],
      slashIdx: ascent.Source[Option[Int]],
      openMenu: ascent.Source[Option[OpenMenu]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      historyShown: Squawk[List[String]],
      historyBrowse: ascent.Source[Option[Int]],
      historyPickIdx: ascent.Source[Option[Int]],
      onDraft: String => UIO[Unit],
      sendDraft: UIO[Unit],
      dropChip: PromptChip => UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
      pickHistory: String => UIO[Unit],
      onMenuKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      toggleTodos: UIO[Unit],
      toggleTasks: UIO[Unit],
      togglePalette: UIO[Unit],
      openPalette: UIO[Unit],
      onPaletteKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onSessionPaneKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onQueueKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      openContext: UIO[Unit],
      requestCancel: ChatModel => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      Composer,
      renderActivityStrip(bridge, chat, nowMs),
      when(chat.map(c => Tasks.running(c.tasks).nonEmpty))(
        E.p(SessionMetaLine, TestId("tasks-status"), chat.map(c => Tasks.statusLine(c.tasks)))
      ),
      renderChipRow(chat, dropChip),
      renderDraft(
        chat,
        draft,
        mentionIdx,
        slashIdx,
        openMenu,
        slashShown,
        mentionShown,
        historyShown,
        historyBrowse,
        historyPickIdx,
        onDraft,
        sendDraft,
        pickMention,
        pickSlash,
        pickHistory,
        onMenuKey,
        toggleTodos,
        toggleTasks,
        togglePalette,
        openPalette,
        onPaletteKey,
        onSessionPaneKey,
        onQueueKey,
      ),
      renderComposerBar(bridge, chat, sendDraft, openContext, requestCancel),
    )

  private def renderActivityStrip(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      nowMs: ascent.Source[Long],
  ): ascent.ast.UI[Any] =
    forEachSignal(Squawk.zipWith(chat, nowMs)((c, n) => TurnActivity.of(c, n).toList))(a => s"${a.kind}-${a.label}") {
      (_, _, activity) =>
        renderActivity(bridge, activity)
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
      slashIdx: ascent.Source[Option[Int]],
      openMenu: ascent.Source[Option[OpenMenu]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      historyShown: Squawk[List[String]],
      historyBrowse: ascent.Source[Option[Int]],
      historyPickIdx: ascent.Source[Option[Int]],
      onDraft: String => UIO[Unit],
      sendDraft: UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
      pickHistory: String => UIO[Unit],
      onMenuKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      toggleTodos: UIO[Unit],
      toggleTasks: UIO[Unit],
      togglePalette: UIO[Unit],
      openPalette: UIO[Unit],
      onPaletteKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onSessionPaneKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onQueueKey: ascent.dom.KeyboardEvent => UIO[Boolean],
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
      Ev.onKeyDown(e =>
        onDraftKey(
          e,
          chat,
          draft,
          mentionIdx,
          slashIdx,
          openMenu,
          slashShown,
          mentionShown,
          historyShown,
          historyBrowse,
          historyPickIdx,
          sendDraft,
          pickMention,
          pickSlash,
          pickHistory,
          onMenuKey,
          toggleTodos,
          toggleTasks,
          togglePalette,
          openPalette,
          onPaletteKey,
          onSessionPaneKey,
          onQueueKey,
        )
      ),
    )

  private def renderComposerBar(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      sendDraft: UIO[Unit],
      openContext: UIO[Unit],
      requestCancel: ChatModel => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      ComposerBar,
      when(chat.map(_.occupancy.exists(_.size > 0)))(
        occupancyEl(chat, openContext)
      ),
      E.button(
        Send,
        TestId("send"),
        A.`type`("button"),
        Ev.onClick(_ =>
          chat.get.flatMap { c =>
            if ChatModel.turnIsRunning(c) then requestCancel(c)
            else sendDraft
          }
        ),
        chat.map(c => if ChatModel.turnIsRunning(c) then "Stop" else "Send"),
      ),
    )

  private def onDraftKey(
      e: ascent.dom.KeyboardEvent,
      chat: ascent.Source[ChatModel],
      draft: ascent.Source[String],
      mentionIdx: ascent.Source[Option[Int]],
      slashIdx: ascent.Source[Option[Int]],
      openMenu: ascent.Source[Option[OpenMenu]],
      slashShown: Squawk[List[SlashCommand]],
      mentionShown: Squawk[List[MentionFile]],
      historyShown: Squawk[List[String]],
      historyBrowse: ascent.Source[Option[Int]],
      historyPickIdx: ascent.Source[Option[Int]],
      sendDraft: UIO[Unit],
      pickMention: MentionFile => UIO[Unit],
      pickSlash: String => UIO[Unit],
      pickHistory: String => UIO[Unit],
      onMenuKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      toggleTodos: UIO[Unit],
      toggleTasks: UIO[Unit],
      togglePalette: UIO[Unit],
      openPalette: UIO[Unit],
      onPaletteKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onSessionPaneKey: ascent.dom.KeyboardEvent => UIO[Boolean],
      onQueueKey: ascent.dom.KeyboardEvent => UIO[Boolean],
  ): UIO[Unit] =
    val key                         = e.key
    val ctrlOrMeta                  = e.ctrlKey || e.metaKey
    def go(z: UIO[Unit]): UIO[Unit] =
      e.preventDefault()
      e.stopPropagation()
      z
    for
      stolen    <- onPaletteKey(e)
      paneKey   <- onSessionPaneKey(e)
      queued    <- onQueueKey(e)
      slash     <- slashShown.get
      mentions  <- mentionShown.get
      history   <- historyShown.get
      idx       <- mentionIdx.get
      slashPick <- slashIdx.get
      pick      <- historyPickIdx.get
      browse    <- historyBrowse.get
      text      <- draft.get
      menu      <- openMenu.get
      c         <- chat.get
      s    = c.settings
      list = PromptHistory.entries(c)
      step = key == "ArrowDown" || key == "ArrowUp" || key == "Home" || key == "End"
      out <-
        if stolen || paneKey || queued then ZIO.unit
        else if menu.isDefined && isMenuNav(key, e.shiftKey) then onMenuKey(e).unit
        else if mentions.nonEmpty && step then go(mentionIdx.set(ComposerQuery.moveIndex(idx, key, mentions.size)))
        else if mentions.nonEmpty && (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
          mentions.lift(idx.getOrElse(0)) match
            case Some(file) => go(pickMention(file))
            case None       => ZIO.unit
        else if history.nonEmpty && step then go(historyPickIdx.set(ComposerQuery.moveIndex(pick, key, history.size)))
        else if history.nonEmpty && (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
          history.lift(pick.getOrElse(0)) match
            case Some(row) => go(pickHistory(row))
            case None      => ZIO.unit
        else if slash.nonEmpty && step then go(slashIdx.set(ComposerQuery.moveIndex(slashPick, key, slash.size)))
        else if slash.nonEmpty && (key == "Enter" || key == "Tab") && !e.shiftKey && !ctrlOrMeta then
          slash.lift(slashPick.getOrElse(0)) match
            case Some(cmd) => go(pickSlash(cmd.name))
            case None      => ZIO.unit
        else if key == "ArrowUp" && mentions.isEmpty && slash.isEmpty && history.isEmpty then
          if list.isEmpty then ZIO.unit
          else
            browse match
              case Some(i) =>
                val n = PromptHistory.older(i, list.size)
                go(historyBrowse.set(Some(n)) *> draft.set(list(n)))
              case None if text.isEmpty && c.chips.isEmpty =>
                go(historyBrowse.set(Some(0)) *> draft.set(list.head))
              case None => ZIO.unit
        else if key == "ArrowDown" && browse.isDefined then
          PromptHistory.newer(browse.get) match
            case None    => go(historyBrowse.set(None) *> draft.set(""))
            case Some(n) => go(historyBrowse.set(Some(n)) *> draft.set(list(n)))
        else if (e.ctrlKey || e.metaKey) && !e.shiftKey && (key == "p" || key == "P") then go(togglePalette)
        else if key == "?" && !ctrlOrMeta && text.isEmpty && c.chips.isEmpty then go(openPalette)
        else if e.ctrlKey && !e.metaKey && !e.shiftKey && (key == "t" || key == "T") && !c.pickerOpen then
          go(toggleTodos)
        else if e.ctrlKey && !e.metaKey && !e.shiftKey && (key == "g" || key == "G") && !c.pickerOpen then
          go(toggleTasks)
        else
          ComposerQuery.sendOnKey(key, e.shiftKey, ctrlOrMeta, s.useCtrlEnterToSend) match
            case ComposerQuery.SendKey.Send    => go(sendDraft)
            case ComposerQuery.SendKey.Newline => ZIO.unit
            case ComposerQuery.SendKey.Ignore  => ZIO.unit
    yield out
    end for
  end onDraftKey

  private def renderActivity(bridge: HostBridge, activity: Squawk[TurnActivity]): ascent.ast.UI[Any] =
    val detail = activity.map(_.detail.getOrElse(""))
    val timer  = activity.map(a => TurnActivity.timerLabel(a.elapsedMs).getOrElse(""))
    val file   = activity.map(_.path.filter(_.nonEmpty).fold("")(UnifiedDiff.fileName))
    E.div(
      ActivityRow,
      TestId("activity"),
      E.span(
        ActivityIcon,
        A.title(activity.map(_.label)),
        E.span(SpinGlyph, "⋅"),
        E.span(SpinGlyph, ":"),
        E.span(SpinGlyph, "⸬"),
        E.span(SpinGlyph, "⁙"),
      ),
      E.span(activity.map(_.label)),
      when(file.map(_.nonEmpty))(
        E.button(
          Chip,
          TestId("activity-file"),
          A.title(activity.map(_.path.getOrElse(""))),
          Ev.onClick(_ => activity.get.flatMap(a => openLocated(bridge, a.path, a.line))),
          file,
        )
      ),
      when(detail.map(_.nonEmpty))(E.pre(TestId("activity-detail"), detail)),
      when(timer.map(_.nonEmpty))(E.span(TestId("activity-timer"), timer)),
    )
  end renderActivity

  private def writeClipboard(text: String): UIO[Unit] =
    ZIO.succeed {
      try
        val _ = Browser.navigator.clipboard.writeText(text)
      catch case _: Throwable => ()
    }

  private def stopRec(slot: AtomicReference[Option[SpeechRecInstance]]): Unit =
    slot.getAndSet(None).foreach { rec =>
      try rec.stop()
      catch case _: Throwable => ()
      try rec.abort()
      catch case _: Throwable => ()
    }

  private def probeMic(): Unit =
    try
      val _ = Browser.navigator.mediaDevices.getUserMedia(MicConstraints())
    catch case _: Throwable => ()

  def isThemeNav(key: String): Boolean =
    key == "ArrowDown" || key == "ArrowUp" || key == "Home" || key == "End" || key == "Enter"

  def paintTheme(id: String): Unit =
    val theme = Theme.canonicalize(id)
    try
      val el = ascent.dom.window.document.documentElement.asInstanceOf[ascent.dom.HTMLElement]
      el.setAttribute("data-theme", theme)
      Theme.All.flatMap(_.vars.keys).toSet.foreach(k => el.style.removeProperty(k))
      Theme.pick(theme).foreach { t =>
        t.vars.foreach { (k, v) => el.style.setProperty(k, v) }
      }
    catch case _: Throwable => ()
  end paintTheme

  def themeInline(id: String): String =
    Theme.pick(id).toList.flatMap(_.vars.toList).map((k, v) => s"$k: $v").mkString("; ")

  def toggleDetails(sel: String): UIO[Unit] =
    ZIO.succeed {
      try
        val el = ascent.dom.window.document.querySelector(sel)
        if el != null then
          val d =
            if el.tagName.toLowerCase == "details" then el
            else el.querySelector("details")
          if d != null then if d.hasAttribute("open") then d.removeAttribute("open") else d.setAttribute("open", "")
      catch case _: Throwable => ()
    }

  def imageFiles(dt: ascent.dom.DataTransfer): List[ascent.dom.File] =
    if dt == null then Nil
    else
      val files = dt.files
      if files == null then Nil
      else
        (0 until files.length).toList
          .map(i => files.item(i))
          .filter(f => f != null && f.`type`.startsWith("image/"))

  def readImage(file: js.Any): UIO[Option[(String, String, String)]] =
    ZIO.async { cb =>
      try
        val named  = file.asInstanceOf[groksbeard.facade.File]
        val reader = new groksbeard.facade.FileReader()
        reader.onload = (_: js.Any) =>
          val raw   = Option(reader.result).map(_.toString).getOrElse("")
          val comma = raw.indexOf(',')
          val data  = if comma >= 0 then raw.substring(comma + 1) else raw
          val mime  = Option(named.`type`).filter(_.nonEmpty).getOrElse("image/png")
          val name  = Option(named.name).filter(_.nonEmpty).getOrElse("paste.png")
          cb(ZIO.succeed(if data.isEmpty then None else Some((mime, data, name))))
        reader.onerror = (_: js.Any) => cb(ZIO.succeed(None))
        reader.readAsDataURL(file)
      catch case _: Throwable => cb(ZIO.succeed(None))
    }

  private def heroLogo(logoSrc: Option[String]): ascent.ast.UI[Any] =
    val src = logoSrc.filter(_.nonEmpty).getOrElse("/logo.png")
    E.img(Logo, TestId("hero-logo"), A.src(src), A.alt("Grok's Beard"))

  private def renderQueue(
      chat: ascent.Source[ChatModel],
      open: ascent.Source[Boolean],
      idx: ascent.Source[Option[Int]],
      toggle: UIO[Unit],
      sendNow: QueueId => UIO[Unit],
      drop: QueueId => UIO[Unit],
      edit: QueuedPrompt => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(chat.map(_.queue.nonEmpty))(
      E.div(
        ChangesPane,
        TestId("queue"),
        E.button(
          ChangesHead,
          TestId("queue-toggle"),
          A.`type`("button"),
          A.title("Toggle queue (Ctrl+4)"),
          Ev.onClick(_ => toggle),
          E.strong(
            chat.map { c =>
              val n = c.queue.size
              if n == 1 then "1 queued" else s"$n queued"
            }
          ),
          E.span(open.map(on => if on then "Hide" else "Show")),
        ),
        when(open)(
          E.div(
            ChangesList,
            TestId("queue-list"),
            A.role("listbox"),
            forEach(
              Squawk.zipWith(chat, idx) { (c, i) =>
                c.queue.zipWithIndex.map { (item, n) => (item, n, i.contains(n)) }
              }
            )(t => s"${t._1.id}-${t._3}") { t =>
              val (item, n, on) = t
              E.div(
                if on then PaletteItemOn else FileRow,
                TestId(s"queue-${item.id}"),
                Ev.onClick(_ => idx.set(Some(n))),
                E.span(QueuedPrompt.display(item)),
                E.button(
                  Chip,
                  TestId(s"queue-now-${item.id}"),
                  A.`type`("button"),
                  Ev.onClick(_ => sendNow(item.id)),
                  "Send now",
                ),
                E.button(
                  Chip,
                  TestId(s"queue-edit-${item.id}"),
                  A.`type`("button"),
                  Ev.onClick(_ => edit(item)),
                  "Edit",
                ),
                E.button(
                  Chip,
                  TestId(s"queue-drop-${item.id}"),
                  A.`type`("button"),
                  Ev.onClick(_ => drop(item.id)),
                  "Drop",
                ),
              )
            },
          )
        ),
      )
    )
  end renderQueue

  private def renderTurn(
      bridge: HostBridge,
      id: String,
      turn: Squawk[TurnView],
      outputs: Squawk[Map[ToolCallId, String]],
      selected: Squawk[Boolean],
      onSelect: UIO[Unit],
      attach: String => UIO[Unit] = _ => ZIO.unit,
  ): ascent.ast.UI[Any] =
    val parts = turn.map(ChatMarkdown.parts)
    val tail  = parts.map(_._2)
    E.section(
      Turn,
      TestId(s"turn-$id"),
      Attr.ReactiveAttr("data-selected", selected.map(on => AttrValue.Str(if on then "true" else "false"))),
      Ev.onClick(_ => onSelect),
      when(turn.map(_.user.exists(_.text.nonEmpty)))(
        E.div(UserMsg, TestId(s"user-$id"), turn.map(userTextOf))
      ),
      when(turn.map(_.thought.nonEmpty))(
        E.details(
          ThoughtBox,
          TestId(s"thought-$id"),
          E.summary(turn.map(t => Thought.summaryLabel(t.thought, t.stopReason.nonEmpty))),
          E.pre(ThoughtBody, turn.map(_.thought)),
        )
      ),
      renderTools(bridge, turn.map(_.tools), outputs),
      when(turn.map(_.agent.nonEmpty))(
        E.div(
          AgentMsg,
          TestId(s"agent-$id"),
          forEach(parts.map(_._1))(_._1) { pair =>
            ChatMarkdown.block(pair._2, pair._1)
          },
          when(tail.map(_.startsWith("```")))(E.pre(E.code(tail))),
          when(tail.map(t => t.nonEmpty && !t.startsWith("```")))(E.p(tail)),
        )
      ),
      when(turn.map(_.subagents.nonEmpty))(
        E.div(
          TestId(s"subagents-$id"),
          forEachSignal(turn.map(_.subagents))(_.id.value) { (sid, _, row) =>
            E.div(
              ToolBox,
              FileRow,
              TestId(s"subagent-$sid"),
              A.role("button"),
              Ev.onClick(_ => attach(sid)),
              E.span(TodoMark, row.map(r => Tasks.mark(r.status))),
              E.span(row.map(Tasks.lifecycle)),
            )
          },
        )
      ),
      when(turn.map(t => t.stopReason.exists(_ != groksbeard.core.StopReason.EndTurn)))(
        E.div(StopReason, turn.map(_.stopReason.map(groksbeard.core.StopReason.wire).getOrElse("")))
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

  private def renderTools(
      bridge: HostBridge,
      tools: Squawk[List[ToolRow]],
      outputs: Squawk[Map[ToolCallId, String]],
  ): ascent.ast.UI[Any] =
    val split = tools.map(ToolView.splitTail(_))
    E.div(
      when(split.map(_._1.nonEmpty))(
        E.details(
          ToolBox,
          E.summary(split.map { case (earlier, _) => ToolView.rollupLabel(earlier.size) }),
          forEachSignal(split.map(_._1))(_.id.value) { (id, _, tool) =>
            renderTool(bridge, id, tool, outputOf(id, tool, outputs))
          },
        )
      ),
      forEachSignal(split.map(_._2))(_.id.value) { (id, _, tool) =>
        renderTool(bridge, id, tool, outputOf(id, tool, outputs))
      },
    )
  end renderTools

  private def outputOf(
      id: String,
      tool: Squawk[ToolRow],
      outputs: Squawk[Map[ToolCallId, String]],
  ): Squawk[String] =
    Squawk.zipWith(tool, outputs) { (t, m) =>
      val live = m.getOrElse(ToolCallId(id), "")
      if live.nonEmpty then live else t.output.getOrElse("")
    }

  private def openLocated(bridge: HostBridge, path: Option[String], line: Option[Int]): UIO[Unit] =
    path.filter(_.nonEmpty) match
      case Some(p) => ZIO.succeed(bridge.post(WebviewMsg.OpenFile(p, line)))
      case None    => ZIO.unit

  private def toolTitle(
      bridge: HostBridge,
      id: String,
      tool: Squawk[ToolRow],
      located: Squawk[Boolean],
  ): ascent.ast.UI[Any] =
    E.span(
      when(located)(
        E.button(
          Chip,
          TestId(s"tool-open-$id"),
          A.title(tool.map(_.path.getOrElse(""))),
          Ev.onClick(_ => tool.get.flatMap(t => openLocated(bridge, t.path, t.line))),
          tool.map(_.title),
        )
      ),
      when(located.map(!_))(E.span(tool.map(_.title))),
    )

  private def renderTool(
      bridge: HostBridge,
      id: String,
      tool: Squawk[ToolRow],
      output: Squawk[String],
  ): ascent.ast.UI[Any] =
    val hasStats = tool.map(t => t.additions.isDefined && t.deletions.isDefined)
    val liveTail = Squawk.zipWith(tool, output) { (t, out) =>
      if !ToolStatus.isLive(t.status) then ""
      else
        Option(out)
          .filter(_.nonEmpty)
          .orElse(t.input.filter(_.nonEmpty))
          .map(s => ToolView.liveTail(s))
          .getOrElse("")
    }
    val located = tool.map(_.path.exists(_.nonEmpty))
    E.div(
      when(hasStats)(
        E.div(
          ToolBox,
          FileRow,
          TestId(s"tool-$id"),
          toolTitle(bridge, id, tool, located),
          E.span(StatAdd, tool.map(t => t.additions.fold("")(a => s"+$a"))),
          E.span(StatDel, tool.map(t => t.deletions.fold("")(d => s"/-$d"))),
          E.button(
            Chip,
            TestId(s"tool-diff-$id"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.OpenDiff(RequestId(id))))),
            "Review",
          ),
        )
      ),
      when(hasStats.map(!_))(
        E.details(
          ToolBox,
          TestId(s"tool-$id"),
          E.summary(
            toolTitle(bridge, id, tool, located),
            when(liveTail.map(_.nonEmpty))(
              E.pre(TestId(s"tool-tail-$id"), liveTail)
            ),
          ),
          when(tool.map(_.input.exists(_.nonEmpty)))(
            E.pre(TestId(s"tool-input-$id"), tool.map(_.input.map(s => ToolView.clip(s)).getOrElse("")))
          ),
          when(output.map(_.nonEmpty))(
            E.pre(TestId(s"tool-output-$id"), output.map(s => ToolView.clip(s)))
          ),
        )
      ),
    )
  end renderTool

  private def renderCards(
      bridge: HostBridge,
      chat: ascent.Source[ChatModel],
      questionDraft: ascent.Source[QuestionDraft],
      onQuestionPick: (QuestionCard, String) => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      Cards,
      TestId("cards"),
      forEach(chat.map(_.permission.toList))(_.requestId.value) { card =>
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
      forEach(chat.map(_.plan.toList))(_.requestId.value) { card =>
        E.div(
          Card,
          TestId("plan"),
          E.div(AgentMsg, TestId("plan-md"), ChatMarkdown.render(card.planMarkdown)),
          E.button(
            Send,
            TestId("plan-approved"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, PlanOutcome.Approved)))),
            "Approve",
          ),
          E.button(
            MenuItem,
            TestId("plan-cancelled"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, PlanOutcome.Cancelled)))),
            "Request changes",
          ),
          E.button(
            MenuItem,
            TestId("plan-abandoned"),
            Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.PlanVerdict(card.requestId, PlanOutcome.Abandoned)))),
            "Abandon",
          ),
        )
      },
      forEachSignal(chat.map(_.question.toList))(_.requestId.value) { (_, card, _) =>
        renderQuestionCard(bridge, card, questionDraft, onQuestionPick)
      },
      forEach(chat.map(_.elicit.toList))(_.requestId.value) { card =>
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

  private def renderForkAsk(bridge: HostBridge, chat: ascent.Source[ChatModel]): ascent.ast.UI[Any] =
    when(chat.map(_.forkAsk.nonEmpty))(
      E.div(
        Card,
        TestId("fork-ask"),
        E.h3("Fork this session"),
        E.p(Copy, "Same workspace or a new git worktree?"),
        E.button(
          Send,
          TestId("fork-same"),
          Ev.onClick { _ =>
            chat.get.flatMap { c =>
              val d = c.forkAsk.getOrElse("")
              chat.update(_.copy(forkAsk = None)) *>
                ZIO.succeed(bridge.post(WebviewMsg.Fork(worktree = false, d)))
            }
          },
          "Same workspace",
        ),
        E.button(
          MenuItem,
          TestId("fork-worktree"),
          Ev.onClick { _ =>
            chat.get.flatMap { c =>
              val d = c.forkAsk.getOrElse("")
              chat.update(_.copy(forkAsk = None)) *>
                ZIO.succeed(bridge.post(WebviewMsg.Fork(worktree = true, d)))
            }
          },
          "New worktree",
        ),
      )
    )

  private def renderQuestionCard(
      bridge: HostBridge,
      card: QuestionCard,
      questionDraft: ascent.Source[QuestionDraft],
      onQuestionPick: (QuestionCard, String) => UIO[Unit],
  ): ascent.ast.UI[Any] =
    val d    = questionDraft.map(QuestionDraft.align(card, _))
    val q    = d.map(QuestionDraft.current(card, _))
    val n    = card.questions.size
    val pos  = d.map(held => s"Question ${held.index + 1} of $n")
    val last = d.map(QuestionDraft.isLast(card, _))
    val opts = d.map { held =>
      QuestionDraft.current(card, held).toList.flatMap { qq =>
        qq.options.zipWithIndex.map { (opt, idx) =>
          val on = held.selected.getOrElse(qq.id, Nil).contains(opt.id)
          (qq.id, opt, idx, on)
        }
      }
    }
    E.div(
      Card,
      TestId("question"),
      E.p(SessionMetaLine, TestId("question-pos"), pos),
      E.p(q.map(_.map(_.prompt).getOrElse(""))),
      forEach(opts)(t => s"${t._1}-${t._2.id}-${t._4}") { t =>
        val (qid, opt, idx, on) = t
        E.button(
          if on then Send else MenuItem,
          TestId(s"question-$qid-${opt.id}"),
          Ev.onClick(_ => onQuestionPick(card, opt.id)),
          s"${idx + 1} ${opt.label}",
        )
      },
      when(q.map(_.exists(_.allowFreeText)))(
        E.textarea(
          TestId("question-freetext"),
          A.placeholder("Or type an answer"),
          A.value(
            d.map { held =>
              QuestionDraft.current(card, held).flatMap(qq => held.freeText.get(qq.id)).getOrElse("")
            }
          ),
          Events.onInput(e => questionDraft.update(QuestionDraft.setFreeText(card, _, e.targetValue.getOrElse("")))),
        )
      ),
      when(d.map(_.index > 0))(
        E.button(
          CardBtn,
          TestId("question-prev"),
          Ev.onClick(_ => questionDraft.update(QuestionDraft.prev(card, _))),
          "Back",
        )
      ),
      when(last.map(isLast => !isLast))(
        E.button(
          CardBtn,
          TestId("question-next"),
          Ev.onClick(_ => questionDraft.update(QuestionDraft.next(card, _))),
          "Next",
        )
      ),
      when(last)(
        E.button(
          Send,
          TestId("question-submit"),
          Ev.onClick(_ =>
            questionDraft.get.flatMap { held =>
              val now = QuestionDraft.align(card, held)
              ZIO.succeed(
                bridge.post(WebviewMsg.QuestionSubmit(card.requestId, QuestionDraft.answers(card, now)))
              )
            }
          ),
          "Send answers",
        )
      ),
      E.button(
        MenuItem,
        TestId("question-dismiss"),
        Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.QuestionDismiss(card.requestId)))),
        "Dismiss",
      ),
    )
  end renderQuestionCard

  private def renderRewind(
      chat: ascent.Source[ChatModel],
      armRewind: RewindPoint => UIO[Unit],
      confirmRewind: UIO[Unit],
      cancelRewind: UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      when(chat.map(_.rewind.nonEmpty))(
        E.ul(
          ComposerMenu,
          TestId("rewind"),
          A.role("listbox"),
          forEach(chat.map(_.rewind))(p => s"${p.promptIndex}-${p.preview}") { point =>
            E.li(
              E.button(
                MenuItem,
                TestId(s"rewind-${point.promptIndex}"),
                Ev.onClick(_ => armRewind(point)),
                point.preview,
              )
            )
          },
        )
      ),
      when(chat.map(_.rewindConfirm.nonEmpty))(
        E.div(
          Cards,
          forEach(chat.map(_.rewindConfirm.toList))(p => s"${p.promptIndex}") { point =>
            E.div(
              Card,
              TestId("rewind-confirm"),
              E.h3("Rewind conversation to this turn?"),
              E.p(Copy, point.preview),
              E.button(
                Send,
                TestId("rewind-yes"),
                Ev.onClick(_ => confirmRewind),
                "Rewind",
              ),
              E.button(
                CardBtn,
                TestId("rewind-no"),
                Ev.onClick(_ => cancelRewind),
                "Cancel",
              ),
            )
          },
        )
      ),
    )
  end renderRewind

  private def renderPalette(
      rows: Squawk[List[PaletteRow]],
      query: ascent.Source[String],
      idx: ascent.Source[Option[Int]],
      close: UIO[Unit],
      pick: PaletteRow => UIO[Unit],
      onQuery: String => UIO[Unit],
      onPaletteKey: ascent.dom.KeyboardEvent => UIO[Boolean],
  ): ascent.ast.UI[Any] =
    E.div(
      PaletteScrim,
      TestId("palette-scrim"),
      Ev.onClick(_ => close),
      E.div(
        PalettePanel,
        TestId("palette"),
        A.role("dialog"),
        Ev.onClick { e =>
          e.stopPropagation()
          ZIO.succeed(Dom.focusFirst(ChatApp.PaletteFilterSel))
        },
        E.input(
          Filter,
          TestId("palette-filter"),
          A.`type`("search"),
          Attr.StaticAttr("autofocus", AttrValue.Str("autofocus")),
          A.value(query),
          A.placeholder("Filter commands"),
          Events.onInput(e => onQuery(e.targetValue.getOrElse(""))),
          Ev.onKeyDown(e => onPaletteKey(e).unit),
          Lifecycle.onMountScoped[ascent.dom.HTMLInputElement, Any](el => ZIO.succeed { el.focus(); () }),
        ),
        E.div(
          PaletteList,
          TestId("palette-list"),
          when(rows.map(_.isEmpty))(E.p(Copy, "No matching commands")),
          forEach(
            Squawk.zipWith(rows, idx) { (list, i) =>
              list.zipWithIndex.map { (row, n) => (row, i.contains(n)) }
            }
          )(t => s"${t._1.id}-${t._2}") { t =>
            val (row, on) = t
            E.button(
              if on then PaletteItemOn else PaletteItem,
              TestId(s"palette-${row.id}"),
              A.`type`("button"),
              Ev.onClick(_ => pick(row)),
              E.span(row.label, E.span(PaletteDesc, row.description)),
              E.span(PaletteHint, row.hint),
            )
          },
        ),
      ),
    )
  end renderPalette

  private def renderMcps(
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
      toggle: (String, Boolean) => UIO[Unit],
  ): ascent.ast.UI[Any] =
    E.div(
      PaletteScrim,
      TestId("mcps-scrim"),
      Ev.onClick(_ => close),
      E.div(
        PalettePanel,
        TestId("mcps"),
        Ev.onClick { e =>
          e.stopPropagation()
          ZIO.unit
        },
        E.div(
          PickerHead,
          E.span(chat.map(c => Mcps.headline(c.mcps))),
          E.button(Chip, TestId("mcps-close"), Ev.onClick(_ => close), "Close"),
        ),
        E.div(
          PaletteList,
          TestId("mcps-list"),
          when(chat.map(_.mcps.isEmpty))(
            E.p(
              Copy,
              TestId("mcps-empty"),
              "No MCP servers. Add one with grok mcp add, or a project .mcp.json.",
            )
          ),
          forEach(chat.map(_.mcps))(row => s"${row.name}-${row.enabled}") { row =>
            E.div(
              PaletteItem,
              TestId(s"mcp-${row.name}"),
              E.span(
                E.strong(row.name),
                E.span(
                  PaletteDesc,
                  s"${Mcps.sourceLabel(row.source)} · ${Mcps.status(row)}",
                ),
              ),
              E.button(
                Chip,
                TestId(s"mcp-toggle-${row.name}"),
                A.`type`("button"),
                Ev.onClick(_ => toggle(row.name, !row.enabled)),
                if row.enabled then "On" else "Off",
              ),
            )
          },
        ),
      ),
    )
  end renderMcps

  private def renderTodos(
      chat: ascent.Source[ChatModel],
      todosOpen: ascent.Source[Boolean],
      toggleTodos: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(todosOpen)(
      E.div(
        ChangesPane,
        TestId("todos"),
        E.button(
          ChangesHead,
          TestId("todos-toggle"),
          A.`type`("button"),
          A.title("Hide todos (Ctrl+T)"),
          Ev.onClick(_ => toggleTodos),
          E.strong(chat.map(c => Todos.headline(c.todos))),
          E.span(
            SessionMetaLine,
            chat.map { c =>
              if c.todos.isEmpty then ""
              else
                val doing = c.todos.find(t => Todos.kind(t.status) == Todos.InProgress).map(_.content)
                doing.getOrElse("")
            },
          ),
          E.span("Hide"),
        ),
        E.div(
          ChangesList,
          TestId("todos-list"),
          when(chat.map(_.todos.isEmpty))(
            E.p(Copy, TestId("todos-empty"), "No todos")
          ),
          forEach(chat.map(_.todos.zipWithIndex))(p => s"${p._2}-${p._1.content}") { pair =>
            val (entry, i) = pair
            val key        = Todos.rowKey(entry, i)
            val kind       = Todos.kind(entry.status)
            val body       =
              kind match
                case Todos.Completed  => E.span(TodoDone, entry.content)
                case Todos.InProgress => E.span(TodoDoing, entry.content)
                case _                => E.span(entry.content)
            E.div(
              FileRow,
              TestId(s"todo-$key"),
              E.span(TodoMark, Todos.mark(entry.status)),
              body,
            )
          },
        ),
      )
    )
  end renderTodos

  private def renderTasks(
      chat: ascent.Source[ChatModel],
      tasksOpen: ascent.Source[Boolean],
      toggleTasks: UIO[Unit],
      stopTask: TaskId => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(tasksOpen)(
      E.div(
        ChangesPane,
        TestId("tasks"),
        E.button(
          ChangesHead,
          TestId("tasks-toggle"),
          A.`type`("button"),
          A.title("Hide tasks (Ctrl+G)"),
          Ev.onClick(_ => toggleTasks),
          E.strong(chat.map(c => Tasks.headline(c.tasks))),
          E.span(SessionMetaLine, chat.map(c => Tasks.statusLine(c.tasks))),
          E.span("Hide"),
        ),
        E.div(
          ChangesList,
          TestId("tasks-list"),
          when(chat.map(_.tasks.isEmpty))(
            E.p(Copy, TestId("tasks-empty"), "No tasks")
          ),
          forEach(chat.map(c => Tasks.grouped(c.tasks)))(g => g._1.getOrElse("rest")) { group =>
            val (heading, rows) = group
            E.div(
              ChangesTurn,
              TestId(heading.fold("tasks-group-rest")(_ => "tasks-group-subagents")),
              heading match
                case Some(title) => E.div(SessionMetaLine, title)
                case None        => E.span()
              ,
              Arg.ArgsArg(
                rows.zipWithIndex.map { (row, i) =>
                  Arg.ChildArg(renderTaskRow(row, i, stopTask))
                }
              ),
            )
          },
        ),
      )
    )
  end renderTasks

  private def renderTaskRow(row: TaskRow, index: Int, stopTask: TaskId => UIO[Unit]): ascent.ast.UI[Any] =
    val key = Tasks.rowKey(row, index)
    E.div(
      FileRow,
      TestId(s"task-$key"),
      E.span(TodoMark, Tasks.mark(row.status)),
      E.span(s"${TaskKind.label(row.kind)} · ${row.label}"),
      if row.detail.nonEmpty then E.span(SessionMetaLine, row.detail) else E.span(),
      if (row.owned || row.kind == TaskKind.Subagent) && TaskStatus.isLive(row.status) then
        E.button(
          Chip,
          TestId(s"task-stop-$key"),
          A.`type`("button"),
          Ev.onClick(_ => stopTask(row.id)),
          "Stop",
        )
      else E.span(),
    )
  end renderTaskRow

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
              val turnKey                = if turnId.nonEmpty then turnId.value else "changes"
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
                        E.span(s"${UnifiedDiff.fileName(file.path)} ${ChangeKind.wire(file.kind)}$region$reason"),
                        statsEl(file.additions, file.deletions),
                        E.button(
                          Chip,
                          TestId(s"change-open-${UnifiedDiff.fileName(file.path)}"),
                          Ev.onClick(_ => ZIO.succeed(bridge.post(WebviewMsg.OpenDiff(RequestId(file.path))))),
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

  private def overlay(id: String, title: String, close: UIO[Unit], body: ascent.ast.UI[Any]*): ascent.ast.UI[Any] =
    E.div(
      PaletteScrim,
      TestId(id),
      Ev.onClick(_ => close),
      E.div(
        PalettePanel,
        Ev.onClick(e => ZIO.succeed(e.stopPropagation())),
        E.div(ChangesHead, E.strong(title), E.button(Chip, TestId(s"$id-close"), Ev.onClick(_ => close), "Close")),
        Arg.ArgsArg(body.toList.map(Arg.ChildArg(_))),
      ),
    )

  private def renderCancelTurn(
      open: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      pick: CancelChoice => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      E.div(
        Cards,
        TestId("cancel-turn"),
        E.div(
          Card,
          E.h3(CancelTurn.Title),
          E.p(Copy, chat.map(c => CancelTurn.heading(CancelTurn.liveSubagents(c.tasks).size))),
          Arg.ArgsArg(
            CancelTurn.rows.map { (n, choice, label) =>
              Arg.ChildArg(
                E.button(
                  CardBtn,
                  TestId(s"cancel-$n"),
                  Ev.onClick(_ => pick(choice)),
                  s"$n. $label",
                )
              )
            }
          ),
        ),
      )
    )

  private def renderChildFrame(
      chat: ascent.Source[ChatModel],
      childDraft: ascent.Source[String],
      childQueue: ascent.Source[Boolean],
      close: UIO[Unit],
      send: String => UIO[Unit],
      onDraft: String => UIO[Unit],
      toggleQueue: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(chat.map(_.attachedChild.nonEmpty))(
      overlay(
        "child-frame",
        "Subagent",
        close,
        E.div(
          Transcript,
          TestId("child-transcript"),
          forEachSignal(chat.map(c => c.attachedChild.toList.flatMap(id => c.children.getOrElse(id.value, Nil))))(
            _.id.value
          ) { (id, _, turn) =>
            E.div(
              TestId(s"child-turn-$id"),
              when(turn.map(_.user.exists(_.text.nonEmpty)))(
                E.div(UserMsg, turn.map(_.user.map(_.text).getOrElse("")))
              ),
              E.div(AgentMsg, turn.map(_.agent)),
            )
          },
        ),
        E.textarea(
          Draft,
          TestId("child-draft"),
          A.value(childDraft),
          Events.onInput(e => onDraft(e.targetValue.getOrElse(""))),
        ),
        E.label(
          TestId("child-queue"),
          E.input(A.`type`("checkbox"), A.checked(childQueue), Ev.onClick(_ => toggleQueue)),
          "Queue",
        ),
        E.button(Send, TestId("child-send"), Ev.onClick(_ => childDraft.get.flatMap(send)), "Send"),
      )
    )

  private def renderAgents(
      open: ascent.Source[Boolean],
      tab: ascent.Source[String],
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
      setTab: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "agents",
        "Agents",
        close,
        E.div(
          ChipRow,
          E.button(
            Chip,
            TestId("agents-tab-agents"),
            Ev.onClick(_ => setTab("agents")),
            "Agents",
          ),
          E.button(
            Chip,
            TestId("agents-tab-personas"),
            Ev.onClick(_ => setTab("personas")),
            "Personas",
          ),
        ),
        when(tab.map(_ == "agents"))(
          E.div(
            TestId("agents-list"),
            forEach(chat.map(_.agents))(_.name) { a =>
              E.button(CardBtn, ThemeChoice, TestId(s"agent-${a.name}"), a.name, E.span(SessionMetaLine, a.description))
            },
          )
        ),
        when(tab.map(_ == "personas"))(
          E.div(
            TestId("personas-list"),
            forEach(chat.map(_.personas))(_.name) { p =>
              E.button(
                CardBtn,
                ThemeChoice,
                TestId(s"persona-${p.name}"),
                p.name,
                E.span(SessionMetaLine, p.description),
              )
            },
          )
        ),
      )
    )

  private def renderPlanView(
      open: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "plan-view",
        "Plan",
        close,
        E.div(AgentMsg, TestId("plan-view-md"), chat.map(c => c.planView.getOrElse("No plan written yet"))),
      )
    )

  private def renderWorkflows(
      open: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "workflows",
        "Workflow runs",
        close,
        forEach(chat.map(_.workflows))(_.name) { run =>
          E.div(
            FileRow,
            TestId(s"workflow-${run.name}"),
            E.strong(run.name),
            E.span(SessionMetaLine, s"${run.phase} · ${run.status}"),
          )
        },
      )
    )

  private def renderDashboard(
      open: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
      openSession: SessionId => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "dashboard",
        "Dashboard",
        close,
        forEach(chat.map(_.dashboard))(_.id.value) { row =>
          E.button(
            CardBtn,
            ThemeChoice,
            TestId(s"dash-${row.id.value}"),
            Ev.onClick(_ => close *> openSession(row.id)),
            row.title,
            E.span(SessionMetaLine, row.state),
          )
        },
      )
    )

  private def renderDoctor(
      open: ascent.Source[Boolean],
      fixOnly: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      close: UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "doctor",
        "Doctor",
        close,
        forEach(
          Squawk.zipWith(chat, fixOnly) { (c, fix) =>
            val rows = if fix then Doctor.fixes(c.doctor) else c.doctor
            if fix && rows.isEmpty then List(("empty", None, true))
            else rows.map(f => (f.id, Some(f), fix))
          }
        )(_._1) { row =>
          val (_, finding, fix) = row
          finding match
            case None    => E.p(Copy, TestId("doctor-fix-empty"), "No automatic fixes.")
            case Some(f) =>
              E.div(
                FileRow,
                TestId(if fix then s"doctor-fix-${f.id}" else s"doctor-${f.id}"),
                E.strong(f.label),
                E.span(SessionMetaLine, if fix then f.fix.getOrElse(f.detail) else f.detail),
              )
        },
      )
    )

  private def renderTheme(
      open: ascent.Source[Boolean],
      chat: ascent.Source[ChatModel],
      preview: ascent.Source[Option[String]],
      close: UIO[Unit],
      set: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    when(open)(
      overlay(
        "theme",
        "Theme",
        close,
        forEach(
          Squawk.zipWith(chat, preview) { (c, p) =>
            val on = p.getOrElse(Theme.canonicalize(c.theme))
            Theme.All.map(t => (t, t.id == on))
          }
        )(_._1.id) { pair =>
          val (t, on) = pair
          E.button(
            if on then Send else CardBtn,
            ThemeChoice,
            TestId(s"theme-${t.id}"),
            Ev.onClick(_ => set(t.id)),
            t.name,
            E.span(SessionMetaLine, t.description),
          )
        },
      )
    )

  private def renderBtw(chat: ascent.Source[ChatModel], close: UIO[Unit]): ascent.ast.UI[Any] =
    when(chat.map(_.btw.nonEmpty))(
      E.div(
        Card,
        TestId("btw"),
        chat.map(_.btw.getOrElse("")),
        E.button(Chip, TestId("btw-close"), Ev.onClick(_ => close), "Close"),
      )
    )

  private def renderVoice(on: ascent.Source[Boolean]): ascent.ast.UI[Any] =
    when(on)(E.div(Toast, TestId("voice"), VoiceCapture.Listening))

  private def renderImages(chat: ascent.Source[ChatModel], drop: String => UIO[Unit]): ascent.ast.UI[Any] =
    when(chat.map(_.images.nonEmpty))(
      E.div(
        ChipRow,
        forEach(chat.map(_.images.zipWithIndex))(_._1.id) { pair =>
          val (img, i) = pair
          E.span(
            Chip,
            TestId(img.id),
            ImageAttach.label(img, i),
            E.button(
              ChipRemove,
              TestId(s"image-remove-${img.id}"),
              A.`type`("button"),
              A.title(s"Remove ${ImageAttach.label(img, i)}"),
              Ev.onClick(_ => drop(img.id)),
              "×",
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
    List(turn.orElse(sum), row.modelId.map(_.value)).flatten.mkString(" · ")

  private def sessionButton(
      row: SessionRow,
      openSession: SessionId => UIO[Unit],
      fade: Boolean,
      armed: Boolean,
      onDelete: SessionId => UIO[Unit],
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
            A.title(row.id.value),
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
      pendingDelete: ascent.Source[Option[SessionId]],
      restore: ascent.Source[Boolean],
      onQuery: String => UIO[Unit],
      close: UIO[Unit],
      openSession: SessionId => UIO[Unit],
      onDelete: SessionId => UIO[Unit],
      toggleRestore: UIO[Unit],
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
      E.label(
        SessionMetaLine,
        TestId("resume-restore"),
        E.input(A.`type`("checkbox"), A.checked(restore), Ev.onClick(_ => toggleRestore)),
        "Restore code",
      ),
      E.div(
        PickerList,
        forEach(shown)(p => s"${p._1.id}-${p._2}-${p._3}") { pair =>
          sessionButton(pair._1, openSession, pair._2, pair._3, onDelete)
        },
      ),
    )
  end renderPicker

  private def occupancyEl(chat: ascent.Source[ChatModel], openContext: UIO[Unit]): ascent.ast.UI[Any] =
    forEach(chat.map(_.occupancy.toList))(o => s"${o.used}/${o.size}") { o =>
      val pct  = Occupancy.percent(o.used, o.size)
      val fill =
        Occupancy.tone(o.used, o.size) match
          case "hot"  => OccupancyFillHot
          case "warn" => OccupancyFillWarn
          case _      => OccupancyFill
      E.button(
        OccupancyMeter,
        TestId("occupancy"),
        A.`type`("button"),
        A.title(s"Context used this session: ${Occupancy.label(o.used, o.size)}. Click for details."),
        Ev.onClick(_ => openContext),
        E.span(
          OccupancyTrack,
          E.span(fill, Attr.StaticAttr("style", AttrValue.Str(s"width:${pct}%;height:100%;display:block"))),
        ),
        E.span(OccupancyCopy, Occupancy.label(o.used, o.size)),
      )
    }

  private def renderSessionPane(
      chat: ascent.Source[ChatModel],
      pane: ascent.Source[Option[SessionPane]],
      close: UIO[Unit],
      open: SessionPane => UIO[Unit],
      copy: String => UIO[Unit],
  ): ascent.ast.UI[Any] =
    val kind = pane.map(_.getOrElse(SessionPane.Info))
    E.div(
      PaletteScrim,
      TestId("session-pane"),
      Ev.onClick(_ => close),
      E.div(
        PalettePanel,
        TestId("session-panel"),
        Ev.onClick { e =>
          e.stopPropagation()
          ZIO.unit
        },
        E.div(
          PickerHead,
          forEach(kind.map(List(_)))(k => k.toString) { k =>
            E.div(
              ChipGroup,
              E.button(
                if k == SessionPane.Info then ChipSegOn else ChipSeg,
                TestId("session-tab-info"),
                A.`type`("button"),
                Ev.onClick(_ => open(SessionPane.Info)),
                "Session",
              ),
              E.button(
                if k == SessionPane.Context then ChipSegOn else ChipSeg,
                ChipSegRule,
                TestId("session-tab-context"),
                A.`type`("button"),
                Ev.onClick(_ => open(SessionPane.Context)),
                "Context",
              ),
            )
          },
          E.button(Chip, TestId("session-close"), Ev.onClick(_ => close), "Close"),
        ),
        E.p(Copy, TestId("session-hint"), kind.map(SessionPane.hint)),
        forEach(kind.map(List(_)))(k => k.toString) { k =>
          val occ = chat.map(SessionFacts.occupancy)
          E.div(
            PaletteList,
            TestId(if k == SessionPane.Context then "context" else "session-info"),
            when(k == SessionPane.Context)(
              forEach(occ.map(_.toList))(o => s"${o.used}/${o.size}") { o =>
                val pct  = Occupancy.percent(o.used, o.size)
                val fill =
                  Occupancy.tone(o.used, o.size) match
                    case "hot"  => OccupancyFillHot
                    case "warn" => OccupancyFillWarn
                    case _      => OccupancyFill
                E.div(
                  ContextTrack,
                  TestId("context-bar"),
                  E.span(fill, Attr.StaticAttr("style", AttrValue.Str(s"width:${pct}%;height:100%;display:block"))),
                )
              }
            ),
            when(k == SessionPane.Context)(
              when(occ.map(_.exists(o => Occupancy.tone(o.used, o.size) != "ok")))(
                E.p(Copy, TestId("context-compact"), "Approaching auto-compact (85%).")
              )
            ),
            forEach(chat.map(c => SessionFacts.rows(k, c)))(r => s"${r.id}-${r.value}") { row =>
              E.button(
                FactRowEl,
                TestId(s"fact-${row.id}"),
                A.`type`("button"),
                A.title(s"Copy ${row.label}"),
                Ev.onClick(_ => copy(row.copy)),
                E.span(FactLabel, row.label),
                E.span(FactValue, row.value),
              )
            },
          )
        },
      ),
    )
  end renderSessionPane
end ChatApp
