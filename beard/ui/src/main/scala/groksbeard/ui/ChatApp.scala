package groksbeard.ui

import ascent.*
import ascent.css.Color
import ascent.css.Styles.*
import ascent.dsl.*
import groksbeard.core.*
import zio.*

enum OpenMenu:
  case Mode, Settings

enum Scene:
  case Empty, Slash, Mentions, Settings

object Scene:
  def from(name: String): Scene =
    name match
      case "slash"    => Scene.Slash
      case "mentions" => Scene.Mentions
      case "settings" => Scene.Settings
      case _          => Scene.Empty

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

  def component(bridge: HostBridge, logoSrc: Option[String], scene: Scene = Scene.Empty): UIO[ascent.ast.UI[Any]] =
    val initialDraft = scene match
      case Scene.Slash    => "/"
      case Scene.Mentions => "@"
      case _              => ""
    val initialMenu = scene match
      case Scene.Settings => Some(OpenMenu.Settings)
      case _              => None
    for
      title        <- sq("Grok's Beard")
      modeId       <- sq("normal")
      modes        <- sq(List.empty[ModeOption])
      draft        <- sq(initialDraft)
      commands     <- sq(List.empty[SlashCommand])
      mentionHost  <- sq("")
      mentionFiles <- sq(List.empty[MentionFile])
      dismissed    <- sq(false)
      mentionIdx   <- sq(Option.empty[Int])
      openMenu     <- sq(initialMenu)
      settings     <- sq(SettingsState.defaults)
    yield
      def run(effect: UIO[Unit]): Unit =
        Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.fork(effect)
          ()
        }

      def applyHost(msg: HostMsg): UIO[Unit] =
        msg match
          case HostMsg.Ready =>
            ZIO.unit
          case HostMsg.SessionMeta(_, nextTitle, nextMode, nextModes) =>
            title.set(nextTitle) *>
              modeId.set(nextMode) *>
              ZIO.when(nextModes.nonEmpty)(modes.set(nextModes)).unit
          case HostMsg.AvailableCommands(next) =>
            commands.set(next)
          case HostMsg.MentionResults(query, files) =>
            mentionHost.set(query) *> mentionFiles.set(files)
          case HostMsg.Settings(state) =>
            settings.set(state)
          case HostMsg.ComposerChip(path, absPath, source) =>
            ZIO.unit

      bridge.onHost(msg => run(applyHost(msg)))
      bridge.post(WebviewMsg.Ready)
      ComposerQuery.mentionQuery(initialDraft).foreach { q =>
        bridge.post(WebviewMsg.MentionQuery(q))
      }

      val slashShown = Squawk.zipWith(draft, commands) { (d, cs) =>
        ComposerQuery.slashQuery(d).map(q => ComposerQuery.filterSlash(cs, q)).getOrElse(Nil)
      }
      val mentionPack  = Squawk.zipWith(mentionHost, mentionFiles)(Tuple2.apply)
      val mentionBase  = Squawk.zipWith(mentionPack, dismissed)(Tuple2.apply)
      val mentionShown = Squawk.zipWith(draft, mentionBase) { (d, pack) =>
        val ((q, files), disc) = pack
        ComposerQuery.mentionChoices(d, q, files, disc)
      }

      def sendDraft: UIO[Unit] =
        draft.get.flatMap { text =>
          val trimmed = text.trim
          if trimmed.isEmpty then ZIO.unit
          else ZIO.succeed(bridge.post(WebviewMsg.Send(trimmed))) *> draft.set("") *> dismissed.set(false)
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
            A.title(modeId.map(ModeLabel.modeTip)),
            Ev.onClick(_ =>
              openMenu.update {
                case Some(OpenMenu.Mode) => None
                case _                   => Some(OpenMenu.Mode)
              }
            ),
            Squawk.zipWith(modeId, modes)(ModeLabel.modeLabel),
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
        E.div(
          Empty,
          logoSrc match
            case Some(src) => E.img(Logo, A.src(src), A.alt("Grok's Beard"))
            case None      => E.span(),
          E.h1(Title, title),
          E.p(Copy, "Ask Grok anything."),
        ),
        when(openMenu.map(_.contains(OpenMenu.Mode)))(
          E.div(
            Popover,
            TestId("mode-menu"),
            forEach(modes)(_.id) { mode =>
              E.button(
                MenuItem,
                TestId(s"mode-${mode.id}"),
                A.title(ModeLabel.modeTip(mode.id)),
                Ev.onClick(_ =>
                  openMenu.set(None) *>
                    modeId.set(mode.id) *>
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
                settings.get.flatMap { s =>
                  val next = !s.useCtrlEnterToSend
                  settings.set(s.copy(useCtrlEnterToSend = next)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetSetting("useCtrlEnterToSend", next)))
                }
              ),
              settings.map(s => if s.useCtrlEnterToSend then "Ctrl+Enter to send: on" else "Ctrl+Enter to send: off"),
            ),
            E.button(
              MenuItem,
              TestId("setting-active-file"),
              Ev.onClick(_ =>
                settings.get.flatMap { s =>
                  val next = !s.includeActiveFileByDefault
                  settings.set(s.copy(includeActiveFileByDefault = next)) *>
                    ZIO.succeed(bridge.post(WebviewMsg.SetSetting("includeActiveFileByDefault", next)))
                }
              ),
              settings.map(s =>
                if s.includeActiveFileByDefault then "Include active file: on" else "Include active file: off"
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
              settings.map { s =>
                if s.useCtrlEnterToSend then "Ctrl/Cmd+Enter sends, Enter inserts a newline"
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
                s        <- settings.get
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
            Ev.onClick(_ => sendDraft),
            "Send",
          ),
        ),
      )
    end for
  end component
end ChatApp
