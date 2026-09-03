package groksbeard.ui

import ascent.*
import ascent.css.Color
import ascent.css.Styles.*
import ascent.dsl.*
import groksbeard.core.{HostBridge, HostMsg, WebviewMsg}
import zio.*

object ChatApp:

  private val fg           = Color.Keyword("var(--vscode-foreground, #f3e6d0)")
  private val muted        = Color.Keyword("var(--vscode-descriptionForeground, #9d9488)")
  private val bg           = Color.Keyword("var(--vscode-sideBar-background, #1a1410)")
  private val inputBg      = Color.Keyword("var(--vscode-input-background, #2a1d16)")
  private val inputFg      = Color.Keyword("var(--vscode-input-foreground, #f3e6d0)")
  private val widgetBorder = Color.Keyword("var(--vscode-widget-border, #3d2a1f)")
  private val orange       = Color.hex("#c24e16")
  private val cream        = Color.hex("#f3e6d0")

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

  object Empty
      extends CssClass(
        display.flex,
        flexDirection.column,
        alignItems.center,
        paddingTop.px(48),
        minHeight.px(220),
      )

  object Logo
      extends CssClass(
        width.px(132),
        height.px(132),
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

  def component(bridge: HostBridge, logoSrc: Option[String]): UIO[ascent.ast.UI[Any]] =
    for
      title <- sq("Grok's Beard")
      draft <- sq("")
    yield
      bridge.onHost {
        case HostMsg.Ready =>
          ()
        case HostMsg.SessionMeta(_, nextTitle, _) =>
          runDiscard(title.set(nextTitle))
      }
      bridge.post(WebviewMsg.Ready)
      E.div(
        Shell,
        Page,
        E.div(
          Empty,
          logoSrc match
            case Some(src) => E.img(Logo, A.src(src), A.alt("Grok's Beard"))
            case None      => E.span(),
          E.h1(Title, title),
          E.p(Copy, "Ask Grok anything."),
        ),
        E.div(
          Composer,
          E.textarea(
            Draft,
            A.placeholder("Message Grok"),
            Events.onInput(e => draft.set(e.targetValue.getOrElse(""))),
          ),
          E.button(
            Send,
            A.`type`("button"),
            Ev.onClick(_ =>
              draft.get.flatMap { text =>
                val trimmed = text.trim
                if trimmed.isEmpty then ZIO.unit
                else ZIO.succeed(bridge.post(WebviewMsg.Send(trimmed))) *> draft.set("")
              }
            ),
            "Send",
          ),
        ),
      )

  private def runDiscard(effect: UIO[Unit]): Unit =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.fork(effect)
      ()
    }
end ChatApp
