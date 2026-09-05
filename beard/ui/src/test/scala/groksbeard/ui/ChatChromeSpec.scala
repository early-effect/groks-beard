package groksbeard.ui

import ascent.History
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
      test("BeardPath reads session and scene from Location") {
        import ascent.Location
        assertTrue(
          BeardPath.sessionId(Location.parse("?session=s1")).contains("s1"),
          BeardPath.sceneName(Location.parse("?scene=slash")).contains("slash"),
          BeardPath.sessionId(Location.root).isEmpty,
        )
      },
      test("live preview paths pin a client id") {
        assertTrue(
          LivePreviewBridge.eventsPath("tab-1") == "/__beard/events?client=tab-1",
          LivePreviewBridge.msgPath("tab-1") == "/__beard/msg?client=tab-1",
        )
      },
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
      test("permission scene shows a live activity row") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Permission)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "activity")
              text <- root.getByTestId("activity").innerText
            yield assertTrue(text.contains("Editing") || text.contains("Waiting"))
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
      test("clicking a session fades that row and keeps welcome order") {
        val bridge = GatedResumeBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "welcome-sessions")
              before = welcomeIds(root)
              _ <- root.button("session-disk-2").click
              _ <- waitSelector(root, """[data-leaving="true"]""")
              during = welcomeIds(root)
              _ <- waitGone(root, "welcome-sessions")
            yield assertTrue(
              before == List("session-preview", "session-disk-1", "session-disk-2"),
              during == before,
            )
          }
        yield result
        end for
      },
      test("clicking a recent session leaves welcome before the snapshot arrives") {
        val bridge = GatedResumeBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _       <- waitPresent(root, "welcome-sessions")
              _       <- root.button("session-disk-1").click
              _       <- waitPresent(root, "session-loading")
              _       <- waitGone(root, "welcome-sessions")
              loading <- root.getByTestId("session-loading").innerText
              chip    <- root.button("sessions").innerText
              _       <- ZIO.succeed(bridge.completeResume())
              user    <- waitPresent(root, "user-resume-turn") *>
                root.getByTestId("user-resume-turn").innerText
              _ <- waitGone(root, "session-loading")
            yield assertTrue(
              loading.contains("Loading session"),
              chip.contains("Effect-TS"),
              user.contains("hello from disk"),
            )
          }
        yield result
        end for
      },
      test("an empty snapshot stays in the session, not welcome") {
        val bridge = GatedResumeBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "welcome-sessions")
              _ <- root.button("session-disk-2").click
              _ <- waitPresent(root, "session-loading")
              _ <- ZIO.succeed(bridge.completeResume(Nil))
              _ <- waitPresent(root, "session-empty")
              _ <- waitGone(root, "welcome-sessions")
              _ <- waitGone(root, "session-loading")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("empty welcome shows the hero logo") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, Some("/logo.png"), Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              el <- waitPresent(root, "hero-logo") *>
                ZIO.succeed(root.element.querySelector("""[data-testid="hero-logo"]"""))
              src = Option(el).map(_.getAttribute("src")).getOrElse("")
            yield assertTrue(src == "/logo.png")
          }
        yield result
        end for
      },
      test("empty welcome still shows a default hero when logoSrc is missing") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              el <- waitPresent(root, "hero-logo") *>
                ZIO.succeed(root.element.querySelector("""[data-testid="hero-logo"]"""))
              src = Option(el).map(_.getAttribute("src")).getOrElse("")
            yield assertTrue(src == "/logo.png")
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
      test("parked follow-up is readable in the transcript") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed(
                bridge.push(HostMsg.Queued(List(QueuedPrompt("q1", "the follow-up I typed"))))
              )
              text <- waitPresent(root, "queue-q1") *> root.getByTestId("queue-q1").innerText
            yield assertTrue(text.contains("Queued"), text.contains("the follow-up I typed"))
          }
        yield result
        end for
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
      test("changes list stays collapsed until Show") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              head <- waitPresent(root, "changes") *> root.getByTestId("changes").innerText
              _    <- waitGone(root, "changes-files")
              _    <- root.button("changes-toggle").click
              row  <- waitPresent(root, "change-Main.scala") *>
                root.getByTestId("change-Main.scala").innerText
            yield assertTrue(
              head.contains("Grok Changes"),
              head.contains("+2/-1 · 1 file"),
              !head.contains("+2/-11"),
              head.contains("Show"),
              row.contains("Main.scala"),
              row.contains("+2/-1"),
            )
          }
        yield result
        end for
      },
      test("changes scene lists the pending file and Keep drops it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "changes-toggle")
              _   <- root.button("changes-toggle").click
              row <- waitPresent(root, "change-Main.scala") *>
                root.getByTestId("change-Main.scala").innerText
              _ <- root.button("change-keep-Main.scala").click
              _ <- waitGone(root, "changes")
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
              _    <- root.button("changes-toggle").click
              _    <- waitPresent(root, "change-open-Main.scala")
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
      test("back from a resumed session returns to welcome") {
        val bridge = PreviewBridge()
        for
          hist   <- History.memory()
          ui     <- ChatApp.component(bridge, None, hist, Scene.Resume)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "sessions")
              _    <- root.button("sessions").click
              _    <- waitPresent(root, "session-picker")
              _    <- root.button("session-disk-1").click
              _    <- waitPresent(root, "user-resume-turn")
              here <- hist.location.get
              _    <- hist.back
              _    <- waitGone(root, "transcript")
            yield assertTrue(BeardPath.sessionId(here).contains("disk-1"))
          }
        yield result
        end for
      },
      test("resume scene lists sessions and picking one restores a turn") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Resume)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "sessions")
              _    <- root.button("sessions").click
              _    <- waitPresent(root, "session-picker")
              _    <- root.button("session-disk-1").click
              user <- waitPresent(root, "user-resume-turn") *>
                root.getByTestId("user-resume-turn").innerText
            yield assertTrue(user.contains("hello from disk"))
          }
        yield result
        end for
      },
      test("New in the toolbar clears a transcript") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "transcript")
              _ <- root.button("new-session").click
              _ <- waitGone(root, "transcript")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("model chip opens a menu and picking one updates the label") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "model")
              before <- root.button("model").innerText
              _      <- root.button("model").click
              _      <- waitPresent(root, "model-menu")
              _      <- root.button("model-grok-code-fast-1").click
              _      <- waitGone(root, "model-menu")
              after  <- waitText(root, "model", "Grok Code Fast")
            yield assertTrue(before.contains("Grok 4.6"), after.contains("Grok Code Fast"))
          }
        yield result
        end for
      },
      test("slash model opens the model menu") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "slash-model")
              _ <- root.button("slash-model").click
              _ <- waitPresent(root, "model-menu")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash resume opens the session picker") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "slash-resume")
              _ <- root.button("slash-resume").click
              _ <- waitPresent(root, "session-picker")
            yield assertTrue(true)
          }
        yield result
        end for
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

  private def welcomeIds(root: AscentRoot): List[String] =
    val nodes = root.element.querySelectorAll("""[data-testid="welcome-sessions"] button""")
    (0 until nodes.length).toList.flatMap { i =>
      Option(nodes.item(i)).collect { case e: ascent.dom.Element =>
        Option(e.getAttribute("data-testid")).filter(s => s != null && s.nonEmpty)
      }.flatten
    }

  private def waitSelector(root: AscentRoot, sel: String)(using Trace): IO[Throwable, Boolean] =
    def loop: IO[Throwable, Boolean] =
      ZIO.succeed(Option(root.element.querySelector(sel))).flatMap {
        case Some(_) => ZIO.succeed(true)
        case None    => ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $sel"))(5.seconds)

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

/** ResumeSession holds the snapshot until [[completeResume]], so tests can see loading chrome. */
final class GatedResumeBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  private var pending: Option[String]   = None
  private val sessions                  = List(
    SessionRow("preview", "New session", activityMs = 20),
    SessionRow(
      "disk-1",
      "Effect-TS Grok Build VS Code Plugin Plan",
      activityMs = 10,
      lastTurn = Some("Continue the plan"),
    ),
    SessionRow("disk-2", "Ascent chat chrome", activityMs = 5, summary = Some("Composer and cards")),
  )

  def post(msg: WebviewMsg): Unit =
    msg match
      case WebviewMsg.Ready =>
        emit(HostMsg.Ready)
        emit(HostMsg.SessionList(sessions, "", openPicker = false))
      case WebviewMsg.ResumeSession(id) =>
        pending = Some(id)
        val title = sessions.find(_.id == id).map(_.title).getOrElse(id)
        emit(HostMsg.ClearTranscript)
        emit(HostMsg.SessionMeta(id, title, "normal"))
      case WebviewMsg.NewSession =>
        emit(HostMsg.ClearTranscript)
        emit(HostMsg.SessionList(sessions, "", openPicker = false))
      case _ => ()

  def completeResume(turns: List[TurnView]): Unit =
    pending.foreach { id =>
      emit(HostMsg.Transcript(turns))
      emit(HostMsg.SessionList(sessions, id, openPicker = false))
    }
    pending = None

  def completeResume(): Unit =
    completeResume(
      List(
        TurnView(
          "resume-turn",
          user = Some(TurnUser("hello from disk")),
          agent = "Resumed.",
          stopReason = Some("end_turn"),
        )
      )
    )

  def onHost(f: HostMsg => Unit): Unit =
    listener = f

  private def emit(msg: HostMsg): Unit =
    listener(msg)
end GatedResumeBridge
