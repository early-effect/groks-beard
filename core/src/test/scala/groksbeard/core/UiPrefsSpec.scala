package groksbeard.core

import java.util.concurrent.atomic.AtomicReference
import zio.*
import zio.test.*

object UiPrefsSpec extends ZIOSpecDefault:
  def spec =
    suite("UiPrefs")(
      test("hydrate reads [ui] from SessionRepo") {
        val disk = new AtomicReference("[ui]\ntheme = \"tokyonight\"\nvim_mode = true\n")
        (for
          prefs  <- ZIO.service[UiPrefs]
          chrome <- prefs.hydrate
        yield assertTrue(chrome.theme == "tokyonight", chrome.vim, !chrome.compact))
          .provide(repo(disk) >+> UiPrefs.layer)
      },
      test("setTheme patches config.toml through SessionRepo") {
        val disk = new AtomicReference("")
        (for
          prefs  <- ZIO.service[UiPrefs]
          chrome <- prefs.setTheme("groknight")
          text   <- ZIO.succeed(disk.get())
        yield assertTrue(
          chrome.theme == "groknight",
          text.contains("[ui]"),
          text.contains("theme = \"groknight\""),
        )).provide(repo(disk) >+> UiPrefs.layer)
      },
      test("patch keepChildren from cancel_subagents_on_turn_cancel") {
        val disk = new AtomicReference("")
        (for
          prefs <- ZIO.service[UiPrefs]
          _     <- prefs.patch("ui", "cancel_subagents_on_turn_cancel", "always_continue")
          cur   <- prefs.current
        yield assertTrue(cur.keepChildren.contains(true))).provide(repo(disk) >+> UiPrefs.layer)
      },
    )

  private def repo(disk: AtomicReference[String]): ULayer[SessionRepo] =
    SessionRepo.test(
      onReadConfig = () => disk.get(),
      onWriteConfig = t => disk.set(t),
    )
end UiPrefsSpec
