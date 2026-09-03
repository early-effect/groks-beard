package groksbeard.ui

import ascent.chekhov.AscentChekhov.withMounted
import ascent.chekhov.AscentRoot
import ascent.chekhov.value
import zio.*
import zio.test.*

object ChatChromeSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(TestAspect.withLiveClock, TestAspect.timeout(30.seconds))

  def spec =
    suite("ChatChrome")(
      test("slash scene lists commands and picking one fills the draft") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _     <- root.button("slash-compact").click
              draft <- waitValue(root, "/compact ")
            yield assertTrue(draft == "/compact ")
          }
        yield result
      },
      test("mentions scene lists files and picking one dismisses the list") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Mentions)
          result <- withMounted(ui) { root =>
            for
              _     <- root.button("mention-src/Main.scala").click
              draft <- waitValue(root, "")
              _     <- waitGone(root, "mentions")
            yield assertTrue(draft == "")
          }
        yield result
        end for
      },
      test("settings toggle flips Ctrl+Enter to send") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Settings)
          result <- withMounted(ui) { root =>
            for
              before <- root.button("setting-ctrl-enter").innerText
              _      <- root.button("setting-ctrl-enter").click
              after  <- waitText(root, "setting-ctrl-enter", "Ctrl+Enter to send: on")
            yield assertTrue(before.contains("off"), after.contains("on"))
          }
        yield result
        end for
      },
      test("Send clears the draft") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- root.textarea("draft").fill("hello")
              _     <- root.button("send").click
              draft <- waitValue(root, "")
            yield assertTrue(draft == "")
          }
        yield result
        end for
      },
    )

  private def waitText(root: AscentRoot, testId: String, expected: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      root.getByTestId(testId).innerText.flatMap { t =>
        if t == expected then ZIO.succeed(t) else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testId == $expected"))(5.seconds)

  private def waitValue(root: AscentRoot, expected: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      root.textarea("draft").value.flatMap { t =>
        if t == expected then ZIO.succeed(t) else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for draft == $expected"))(5.seconds)

  private def waitGone(root: AscentRoot, testId: String)(using Trace): IO[Throwable, Unit] =
    def loop: IO[Throwable, Unit] =
      ZIO.succeed(Option(root.element.querySelector(s"""[data-testid="$testId"]"""))).flatMap {
        case None    => ZIO.unit
        case Some(_) => ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testId to disappear"))(5.seconds)
end ChatChromeSpec
