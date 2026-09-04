package groksbeard.ui

import ascent.chekhov.AscentChekhov.withMounted
import ascent.chekhov.AscentRoot
import ascent.chekhov.value
import groksbeard.core.*
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
      test("mentions scene lists files and picking one chips the path") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Mentions)
          result <- withMounted(ui) { root =>
            for
              _     <- root.button("mention-src/Main.scala").click
              draft <- waitValue(root, "")
              _     <- waitGone(root, "mentions")
              chip  <- waitPresent(root, "chip-src/Main.scala") *>
                root.getByTestId("chip-src/Main.scala").innerText
            yield assertTrue(draft == "", chip.contains("@src/Main.scala"))
          }
        yield result
        end for
      },
      test("removing a mention chip hides the chip row") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Mentions)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("mention-src/Main.scala").click
              _ <- waitPresent(root, "chip-src/Main.scala")
              _ <- root.button("chip-remove-src/Main.scala").click
              _ <- waitGone(root, "chips")
            yield assertTrue(true)
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
      test("permission Esc parks the card without answering") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Permission)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "permission")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "permission")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("permission 1 picks the first option") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Permission)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "permission")
              _ <- root.textarea("draft").press("1")
              _ <- waitGone(root, "permission")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("occupancy from sessionMeta paints the toolbar") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed(
                bridge.push(HostMsg.SessionMeta("s", "Grok's Beard", "normal", occupancy = Some(Occupancy(80, 500))))
              )
              text <- waitPresent(root, "occupancy") *> root.getByTestId("occupancy").innerText
            yield assertTrue(text.contains("80"), text.contains("500"))
          }
        yield result
        end for
      },
      test("permission Allow dismisses the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Permission)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("perm-allow").click
              _ <- waitGone(root, "permission")
            yield assertTrue(true)
          }
        yield result
      },
      test("plan Approve dismisses the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Plan)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("plan-approved").click
              _ <- waitGone(root, "plan")
            yield assertTrue(true)
          }
        yield result
      },
      test("question option dismisses the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("question-style-dense").click
              _ <- waitGone(root, "question")
            yield assertTrue(true)
          }
        yield result
      },
      test("transcript scene shows the user turn") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            root.getByTestId("user-t1").innerText.map(t => assertTrue(t.contains("Summarize Main.scala")))
          }
        yield result
      },
      test("changes scene lists the pending file and Keep drops it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              row <- root.getByTestId("change-Main.scala").innerText
              _   <- root.button("change-keep-Main.scala").click
              _   <- waitGone(root, "changes")
            yield assertTrue(row.contains("Main.scala"), row.contains("+2/-1"))
          }
        yield result
        end for
      },
      test("Review on an edit tool paints the sidebar diff") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              _    <- root.button("tool-diff-call_1").click
              _    <- waitPresent(root, "diff")
              text <- root.getByTestId("diff").innerText
            yield assertTrue(text.contains("def run"))
          }
        yield result
        end for
      },
      test("Open on a change paints a sidebar diff") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              _    <- root.button("change-open-Main.scala").click
              _    <- waitPresent(root, "diff")
              text <- root.getByTestId("diff").innerText
            yield assertTrue(text.contains("def run"), text.contains("object Main"))
          }
        yield result
        end for
      },
      test("permission Open diff paints the reviewer") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Permission)
          result <- withMounted(ui) { root =>
            for
              _    <- root.button("open-diff").click
              body <- waitPresent(root, "diff")
            yield assertTrue(body)
          }
        yield result
      },
      test("Send shows the user turn and agent echo") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- root.textarea("draft").fill("hello")
              _     <- root.button("send").click
              draft <- waitValue(root, "")
              user  <- waitPresent(root, "user-preview-turn") *>
                root.getByTestId("user-preview-turn").innerText
              agent <- waitPresent(root, "agent-preview-turn") *>
                root.getByTestId("agent-preview-turn").innerText
            yield assertTrue(draft == "", user.contains("hello"), agent.contains("hello"))
          }
        yield result
        end for
      },
      test("a live ACP burst still paints the agent reply") {
        val bridge   = PushBridge()
        val commands = HostMsg.AvailableCommands(
          (1 to 40).toList.map(i => SlashCommand(s"skill-$i", "hint " * 40))
        )
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("turn_1", "hello"))
                bridge.push(commands)
                List("The", " user", " wants", " hi").foreach(t => bridge.push(HostMsg.ThoughtChunk("turn_1", t)))
                bridge.push(HostMsg.AgentChunk("turn_1", "Hi."))
                bridge.push(commands)
                bridge.push(HostMsg.TurnEnd("turn_1", "end_turn"))
              }
              user  <- waitPresent(root, "user-turn_1") *> root.getByTestId("user-turn_1").innerText
              agent <- waitPresent(root, "agent-turn_1") *> root.getByTestId("agent-turn_1").innerText
            yield assertTrue(user.contains("hello"), agent.contains("Hi"))
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

  private def waitPresent(root: AscentRoot, testId: String)(using Trace): IO[Throwable, Boolean] =
    def loop: IO[Throwable, Boolean] =
      ZIO.succeed(Option(root.element.querySelector(s"""[data-testid="$testId"]"""))).flatMap {
        case Some(_) => ZIO.succeed(true)
        case None    => ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testId"))(5.seconds)

  private def waitGone(root: AscentRoot, testId: String)(using Trace): IO[Throwable, Unit] =
    def loop: IO[Throwable, Unit] =
      ZIO.succeed(Option(root.element.querySelector(s"""[data-testid="$testId"]"""))).flatMap {
        case None    => ZIO.unit
        case Some(_) => ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testId to disappear"))(5.seconds)
end ChatChromeSpec

/** Pushes HostMsg the way EventSource onmessage does: many callbacks, no backpressure. */
final class PushBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  def post(msg: WebviewMsg): Unit       = ()
  def onHost(f: HostMsg => Unit): Unit  = listener = f
  def push(msg: HostMsg): Unit          = listener(msg)
