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
    Chunk(TestAspect.sequential, TestAspect.withLiveClock, TestAspect.timeout(30.seconds))

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
      test("picking slash fork asks same workspace or worktree") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("slash-fork").click
              _ <- waitPresent(root, "fork-ask")
              _ <- root.button("fork-same").click
              _ <- waitGone(root, "fork-ask")
              _ <- waitPresent(root, "user-preview-turn")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash arrows move the highlight and Enter picks it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "slash-compact")
              _    <- waitPresent(root, "slash-always-approve")
              _    <- root.textarea("draft").press("ArrowDown")
              _    <- root.textarea("draft").press("Enter")
              mode <- waitText(root, "mode", "Always approve")
            yield assertTrue(mode == "Always approve")
          }
        yield result
        end for
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
      test("settings share Grok defaults on and toggles off") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Settings)
          result <- withMounted(ui) { root =>
            for
              before <- root.button("setting-share-backend").innerText
              _      <- root.button("setting-share-backend").click
              after  <- waitText(root, "setting-share-backend", "Share Grok: off")
            yield assertTrue(before.contains("on"), after.contains("off"))
          }
        yield result
        end for
      },
      test("settings arrows from the composer move the highlight") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Settings)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "settings-panel")
              _     <- root.textarea("draft").press("ArrowDown")
              _     <- root.textarea("draft").press("Enter")
              after <- waitText(root, "setting-ctrl-enter", "Ctrl+Enter to send: on")
            yield assertTrue(after.contains("on"))
          }
        yield result
        end for
      },
      test("settings Esc closes the panel") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Settings)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "settings-panel")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "settings-panel")
            yield assertTrue(true)
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
      test("occupancy click opens the context pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "occupancy")
              _    <- root.button("occupancy").click
              used <- waitPresent(root, "context") *> waitPresent(root, "fact-used") *>
                root.getByTestId("fact-used").innerText
            yield assertTrue(used.contains("12k"))
          }
        yield result
        end for
      },
      test("session-info scene lists the session id and copies it with c") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.SessionInfo)
          result <- withMounted(ui) { root =>
            for
              id <- waitPresent(root, "session-info") *> waitPresent(root, "fact-session") *>
                root.getByTestId("fact-session").innerText
              _     <- root.textarea("draft").press("c")
              toast <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(id.contains("01a04ead-8d8e-7e92-9824-3e8580203167"), toast.contains("Copied"))
          }
        yield result
        end for
      },
      test("context scene Esc closes the pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Context)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "context")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "session-pane")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("session-info Tab switches to context") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.SessionInfo)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "session-info")
              _ <- root.textarea("draft").press("Tab")
              _ <- waitPresent(root, "context")
              _ <- waitGone(root, "session-info")
            yield assertTrue(true)
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
      test("plan card renders headings, lists, and tables") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Plan)
          result <- withMounted(ui) { root =>
            for
              _  <- waitPresent(root, "plan")
              el <- ZIO.succeed(root.element.querySelector("""[data-testid="plan-md"]"""))
              h1   = Option(el.querySelector("h1")).exists(_.textContent.contains("Plan"))
              ol   = Option(el.querySelector("ol li")).exists(_.textContent.contains("Port transcript"))
              nest = Option(el.querySelector("ol ul li")).exists(_.textContent.contains("keep the card readable"))
              plus =
                val nodes = el.querySelectorAll("ul li")
                (0 until nodes.length).exists { i =>
                  Option(nodes.item(i)).exists(_.textContent.contains("Keep the list nested"))
                }
              tbl = Option(el.querySelector("""[data-testid^="md-table-"]""")).exists(_.textContent.contains("cards"))
            yield assertTrue(h1, ol, nest, plus, tbl)
          }
        yield result
        end for
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
      test("plan Esc abandons the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Plan)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "plan")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "plan")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("elicit Esc declines the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Elicit)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "elicit")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "elicit")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("question option dismisses the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "question")
              _   <- root.button("question-style-dense").click
              pos <- waitText(root, "question-pos", "Question 2 of 3")
              _   <- root.button("question-extras-wrap").click
              _   <- root.button("question-next").click
              _   <- waitText(root, "question-pos", "Question 3 of 3")
              _   <- root.button("question-note-skip").click
              _   <- waitGone(root, "question")
            yield assertTrue(pos.contains("2 of 3"))
          }
        yield result
        end for
      },
      test("question Dismiss skips the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "question")
              _ <- root.button("question-dismiss").click
              _ <- waitGone(root, "question")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("question Esc dismisses the card") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "question")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "question")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("question Back returns to the previous item") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "question")
              _   <- root.button("question-style-dense").click
              _   <- waitText(root, "question-pos", "Question 2 of 3")
              _   <- root.button("question-prev").click
              pos <- waitText(root, "question-pos", "Question 1 of 3")
            yield assertTrue(pos.contains("1 of 3"))
          }
        yield result
        end for
      },
      test("question Send answers submits free text") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Question)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "question")
              _   <- root.button("question-next").click
              _   <- root.button("question-next").click
              _   <- waitText(root, "question-pos", "Question 3 of 3")
              _   <- root.textarea("question-freetext").press("h")
              pos <- waitText(root, "question-pos", "Question 3 of 3")
              _   <- root.textarea("question-freetext").fill("hello")
              _   <- root.button("question-submit").click
              _   <- waitGone(root, "question")
            yield assertTrue(pos.contains("3 of 3"))
          }
        yield result
        end for
      },
      test("parked follow-up is readable in the queue pane") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed(
                bridge.push(HostMsg.Queued(List(QueuedPrompt(QueueId("q1"), "the follow-up I typed"))))
              )
              text <- waitPresent(root, "queue-q1") *> root.getByTestId("queue-q1").innerText
            yield assertTrue(text.contains("the follow-up I typed"))
          }
        yield result
        end for
      },
      test("queue scene lists parked follow-ups") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "queue")
              one <- waitPresent(root, "queue-q1") *> root.getByTestId("queue-q1").innerText
              two <- waitPresent(root, "queue-q2") *> root.getByTestId("queue-q2").innerText
            yield assertTrue(one.contains("then run the tests"), two.contains("then open the PR"))
          }
        yield result
        end for
      },
      test("queue Drop removes a row") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "queue-q1")
              _ <- root.button("queue-drop-q1").click
              _ <- waitGone(root, "queue-q1")
              _ <- waitPresent(root, "queue-q2")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("queue Edit fills the composer") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "queue-q1")
              _     <- root.button("queue-edit-q1").click
              draft <- waitValue(root, "then run the tests")
              _     <- waitGone(root, "queue-q1")
            yield assertTrue(draft == "then run the tests")
          }
        yield result
        end for
      },
      test("queue Esc hides the list") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "queue-list")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "queue-list")
              _ <- waitPresent(root, "queue")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("queue arrows then Enter send the highlighted follow-up") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "queue-q1")
              _ <- waitPresent(root, "queue-q2")
              _ <- root.textarea("draft").press("ArrowDown")
              _ <- root.textarea("draft").press("Enter")
              _ <- waitGone(root, "queue-q2")
              _ <- waitPresent(root, "queue-q1")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("empty Enter sends the top queued follow-up now") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Queue)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "queue-q1")
              _ <- root.textarea("draft").press("Enter")
              _ <- waitGone(root, "queue-q1")
              _ <- waitPresent(root, "queue-q2")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("a host decode error is visible") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _    <- ZIO.succeed(bridge.push(HostMsg.Error("Could not read host message (boom).", Some(Wire.Decode))))
              text <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(text.contains("Could not read host message"))
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
      test("transcript scene renders headings, tables, fences, and lists") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              agent <- waitPresent(root, "agent-t1") *> root.getByTestId("agent-t1").innerText
              el    <- ZIO.succeed(root.element.querySelector("""[data-testid="agent-t1"]"""))
              tables = el.querySelectorAll("""[data-testid^="md-table-"]""")
              table  = Option(el.querySelector("""[data-testid^="md-table-"]""")).map(_.textContent).getOrElse("")
              row    = Option(el.querySelector("""[data-testid^="md-row-"]""")).map(_.textContent).getOrElse("")
              nested = Option(el.querySelector("ul ul li")).exists(_.textContent.contains("nested boot"))
              plus   = agent.contains("extra path")
              quotes = el.querySelectorAll("blockquote p").length
              ol     = Option(el.querySelector("ol li")).exists(_.textContent.contains("Prefer the common setting"))
              fence  = Option(el.querySelector("pre code")).exists(_.textContent.contains("no ThisBuild"))
              jsHref = Option(el.querySelector("""a[href^="javascript:"]""")).isEmpty
              https  = Option(el.querySelector("""a[href^="https://"]"""))
            yield assertTrue(
              agent.contains("What sbt 2.x actually says"),
              agent.contains("entry is"),
              table.contains("What"),
              table.contains("Value"),
              row.contains("3.9.0"),
              tables.length == 2,
              nested,
              plus,
              quotes == 2,
              ol,
              fence,
              jsHref,
              https.exists(_.getAttribute("target") == "_blank"),
              https.exists(_.getAttribute("rel").contains("noopener")),
            )
          }
        yield result
        end for
      },
      test("a tool path in the transcript is clickable") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "tool-open-read-1")
              text <- root.button("tool-open-read-1").innerText
              _    <- root.button("tool-open-read-1").click
            yield assertTrue(text.contains("Main.scala"))
          }
        yield result
        end for
      },
      test("up on an empty composer recalls the last prompt") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "user-t1")
              _     <- root.textarea("draft").press("ArrowUp")
              draft <- waitValue(root, "Summarize Main.scala")
              _     <- root.textarea("draft").press("ArrowDown")
              empty <- waitValue(root, "")
            yield assertTrue(draft == "Summarize Main.scala", empty.isEmpty)
          }
        yield result
        end for
      },
      test("slash copy toasts the last reply") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "user-t1")
              _      <- root.textarea("draft").fill("/copy")
              _      <- root.button("send").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status == "Copied!")
          }
        yield result
        end for
      },
      test("slash export toasts the conversation copy") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "user-t1")
              _      <- root.textarea("draft").fill("/export")
              _      <- root.button("send").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status == "Conversation copied to clipboard")
          }
        yield result
        end for
      },
      test("copy to a path toasts the file") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "user-t1")
              _      <- root.textarea("draft").fill("/copy notes.md")
              _      <- root.button("send").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status == "Copied to notes.md")
          }
        yield result
        end for
      },
      test("copy with no replies toasts no assistant messages") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "slash-copy")
              _      <- root.button("slash-copy").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status == "No assistant messages to copy")
          }
        yield result
        end for
      },
      test("slash history lists this session's prompts") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "user-t1")
              _     <- root.textarea("draft").fill("/history")
              _     <- waitPresent(root, "history")
              _     <- root.button("history-0").click
              draft <- waitValue(root, "Summarize Main.scala")
            yield assertTrue(draft == "Summarize Main.scala")
          }
        yield result
        end for
      },
      test("transcript follows the tail until the user scrolls up") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                (1 to 12).foreach { i =>
                  bridge.push(HostMsg.UserMessage(s"t$i", s"prompt $i"))
                  bridge.push(HostMsg.AgentChunk(s"t$i", "reply\n" * 8))
                  bridge.push(HostMsg.TurnEnd(s"t$i", "end_turn"))
                }
              }
              _  <- waitPresent(root, "transcript")
              el <- ZIO.succeed(
                root.element.queryHtml("""[data-testid="transcript"]""")
              )
              _ <- ZIO.succeed(el.setAttribute("style", "max-height:140px;overflow-y:auto"))
              _ <- ZIO.succeed {
                el.scrollTop = el.scrollHeight.toDouble
                ChatChromeSpec.fireScroll(el)
              }
              _ <- waitSelector(root, """[data-testid="transcript"][data-follow="true"]""")
              _ <- ZIO.succeed {
                el.scrollTop = 0
                ChatChromeSpec.fireScroll(el)
              }
              _    <- waitSelector(root, """[data-testid="transcript"][data-follow="false"]""")
              held <- ZIO.succeed(el.scrollTop)
              _    <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("later", "after unstick"))
                bridge.push(HostMsg.AgentChunk("later", "still going"))
                bridge.push(HostMsg.TurnEnd("later", "end_turn"))
              }
              _         <- waitPresent(root, "user-later")
              stillHeld <- ZIO.succeed(el.scrollTop)
              _         <- ZIO.succeed {
                el.scrollTop = el.scrollHeight.toDouble
                ChatChromeSpec.fireScroll(el)
              }
              restuck <- waitSelector(root, """[data-testid="transcript"][data-follow="true"]""")
            yield assertTrue(held < 32, stillHeld < 32, restuck)
          }
        yield result
        end for
      },
      test("jump-tail click resticks follow") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                (1 to 12).foreach { i =>
                  bridge.push(HostMsg.UserMessage(s"t$i", s"prompt $i"))
                  bridge.push(HostMsg.AgentChunk(s"t$i", "reply\n" * 8))
                  bridge.push(HostMsg.TurnEnd(s"t$i", "end_turn"))
                }
              }
              _  <- waitPresent(root, "transcript")
              el <- ZIO.succeed(root.element.queryHtml("""[data-testid="transcript"]"""))
              _  <- ZIO.succeed(el.setAttribute("style", "max-height:140px;overflow-y:auto"))
              _  <- ZIO.succeed {
                el.scrollTop = el.scrollHeight.toDouble
                ChatChromeSpec.fireScroll(el)
              }
              _ <- waitSelector(root, """[data-testid="transcript"][data-follow="true"]""")
              _ <- ZIO.succeed {
                el.scrollTop = 0
                ChatChromeSpec.fireScroll(el)
              }
              _   <- waitSelector(root, """[data-testid="transcript"][data-follow="false"]""")
              _   <- waitPresent(root, "jump-tail")
              _   <- root.button("jump-tail").click
              on  <- waitSelector(root, """[data-testid="transcript"][data-follow="true"]""")
              top <- waitScrollTop(root, """[data-testid="transcript"]""", 32)
            yield assertTrue(on, top > 32)
          }
        yield result
        end for
      },
      test("Ctrl+End from the draft resticks follow") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                (1 to 12).foreach { i =>
                  bridge.push(HostMsg.UserMessage(s"t$i", s"prompt $i"))
                  bridge.push(HostMsg.AgentChunk(s"t$i", "reply\n" * 8))
                  bridge.push(HostMsg.TurnEnd(s"t$i", "end_turn"))
                }
              }
              _  <- waitPresent(root, "transcript")
              el <- ZIO.succeed(root.element.queryHtml("""[data-testid="transcript"]"""))
              _  <- ZIO.succeed(el.setAttribute("style", "max-height:140px;overflow-y:auto"))
              _  <- ZIO.succeed {
                el.scrollTop = 0
                ChatChromeSpec.fireScroll(el)
              }
              _   <- waitSelector(root, """[data-testid="transcript"][data-follow="false"]""")
              _   <- ZIO.succeed(fireCtrlKey(root, "draft", "End", "End"))
              on  <- waitSelector(root, """[data-testid="transcript"][data-follow="true"]""")
              top <- waitScrollTop(root, """[data-testid="transcript"]""", 32)
            yield assertTrue(on, top > 32)
          }
        yield result
        end for
      },
      test("todos scene lists entries and Hide dismisses the pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Todos)
          result <- withMounted(ui) { root =>
            for
              head <- waitPresent(root, "todos") *> root.getByTestId("todos").innerText
              row  <- waitPresent(root, "todo-2") *> root.getByTestId("todo-2").innerText
              done <- waitPresent(root, "todo-1") *> root.getByTestId("todo-1").innerText
              _    <- root.button("todos-toggle").click
              _    <- waitGone(root, "todos")
            yield assertTrue(
              head.contains("Todos 2/3"),
              head.contains("Wire ACP plan updates"),
              row.contains("Wire ACP plan updates"),
              done.contains("Checkout the branch"),
            )
          }
        yield result
        end for
      },
      test("Ctrl+T toggles the todos pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Todos)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "todos-list")
              _ <- ZIO.succeed(fireCtrlT(root, "draft"))
              _ <- waitGone(root, "todos")
              _ <- ZIO.succeed(fireCtrlT(root, "draft"))
              _ <- waitPresent(root, "todos-list")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("tasks scene lists running work and Hide dismisses the pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Tasks)
          result <- withMounted(ui) { root =>
            for
              head   <- waitPresent(root, "tasks") *> root.getByTestId("tasks").innerText
              status <- waitPresent(root, "tasks-status") *> root.getByTestId("tasks-status").innerText
              row    <- waitPresent(root, "task-loop-1") *> root.getByTestId("task-loop-1").innerText
              _      <- root.button("tasks-toggle").click
              _      <- waitGone(root, "tasks")
            yield assertTrue(
              head.contains("Tasks 3 running"),
              status.contains("1 command"),
              status.contains("1 loop"),
              status.contains("1 subagent"),
              row.contains("Check CI"),
            )
          }
        yield result
        end for
      },
      test("Ctrl+G toggles the tasks pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Tasks)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "tasks-list")
              _ <- ZIO.succeed(fireCtrlG(root, "draft"))
              _ <- waitGone(root, "tasks")
              _ <- ZIO.succeed(fireCtrlG(root, "draft"))
              _ <- waitPresent(root, "tasks-list")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("tasks scene groups subagents and pins a transcript block") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Tasks)
          result <- withMounted(ui) { root =>
            for
              group <- waitPresent(root, "tasks-group-subagents") *> root.getByTestId("tasks-group-subagents").innerText
              pane  <- waitPresent(root, "task-sub-1") *> root.getByTestId("task-sub-1").innerText
              block <- waitPresent(root, "subagent-sub-1") *> root.getByTestId("subagent-sub-1").innerText
            yield assertTrue(
              group.contains("Subagents"),
              pane.contains("Research spawn_subagent"),
              block.contains("Subagent running"),
              block.contains("Research spawn_subagent"),
            )
          }
        yield result
        end for
      },
      test("a live task update opens the tasks pane") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitGone(root, "tasks")
              _ <- ZIO.succeed {
                bridge.push(
                  HostMsg.Tasks(
                    List(TaskRow(TaskId("t1"), TaskKind.Command, TaskStatus.Running, "sbt compile"))
                  )
                )
              }
              head <- waitPresent(root, "tasks") *> root.getByTestId("tasks").innerText
              row  <- waitPresent(root, "task-t1") *> root.getByTestId("task-t1").innerText
            yield assertTrue(head.contains("Tasks 1 running"), row.contains("sbt compile"))
          }
        yield result
        end for
      },
      test("a live subagent update pins a transcript block and completes in place") {
        val bridge = PushBridge()
        val live   =
          TaskRow(
            TaskId("sub-1"),
            TaskKind.Subagent,
            TaskStatus.Running,
            "Research spawn_subagent",
            "explore · grok-4.6",
          )
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- ZIO.succeed(bridge.push(HostMsg.UserMessage("t1", "research")))
              _     <- waitPresent(root, "turn-t1")
              _     <- ZIO.succeed(bridge.push(HostMsg.Tasks(List(live))))
              group <- waitContains(root, "tasks-group-subagents", "Subagents")
              block <- waitContains(root, "subagent-sub-1", "Subagent running")
              _     <- ZIO.succeed(bridge.push(HostMsg.Tasks(List(live.copy(status = TaskStatus.Completed)))))
              done  <- waitContains(root, "subagent-sub-1", "Subagent completed")
              _     <- waitGone(root, "tasks")
              _     <- ZIO.succeed(fireCtrlG(root, "draft"))
              hist  <- waitPresent(root, "task-sub-1") *> root.getByTestId("task-sub-1").innerText
            yield assertTrue(
              group.contains("Subagents"),
              block.contains("Subagent running"),
              done.contains("Subagent completed"),
              !done.contains("Task completed"),
              hist.contains("Research spawn_subagent"),
            )
          }
        yield result
        end for
      },
      test("a live plan update opens the todos pane") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitGone(root, "todos")
              _ <- ZIO.succeed {
                bridge.push(
                  HostMsg.Todos(
                    List(
                      TodoEntry("Checkout branch", Todos.InProgress, "medium"),
                      TodoEntry("Write tests", Todos.Pending, "high"),
                    )
                  )
                )
              }
              head <- waitPresent(root, "todos") *> root.getByTestId("todos").innerText
              row  <- waitPresent(root, "todo-1") *> root.getByTestId("todo-1").innerText
              _    <- ZIO.succeed {
                bridge.push(
                  HostMsg.Todos(
                    List(
                      TodoEntry("Checkout branch", Todos.Completed, "medium"),
                      TodoEntry("Write tests", Todos.Completed, "high"),
                    )
                  )
                )
              }
              _ <- waitGone(root, "todos")
              _ <- ZIO.succeed(fireCtrlT(root, "draft"))
              _ <- waitPresent(root, "todos-list")
            yield assertTrue(head.contains("Todos 1/2"), row.contains("Checkout branch"))
          }
        yield result
        end for
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
      test("changes Keep all drops the pending turn") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Changes)
          result <- withMounted(ui) { root =>
            for
              _ <- root.button("changes-toggle").click
              _ <- waitPresent(root, "change-turn-t3")
              _ <- root.button("change-keep-all-t3").click
              _ <- waitGone(root, "changes")
            yield assertTrue(true)
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
              _      <- waitPresent(root, "effort")
              _      <- root.button("model").click
              _      <- waitPresent(root, "model-menu")
              _      <- root.button("model-grok-code-fast-1").click
              _      <- waitGone(root, "model-menu")
              after  <- waitText(root, "model", "Grok Code Fast")
              _      <- waitGone(root, "effort")
            yield assertTrue(before.contains("Grok 4.6"), after.contains("Grok Code Fast"))
          }
        yield result
        end for
      },
      test("effort arrows move the highlight and Enter picks it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "effort")
              _     <- root.button("effort").click
              _     <- waitPresent(root, "effort-menu")
              _     <- root.textarea("draft").press("ArrowDown")
              _     <- root.textarea("draft").press("Enter")
              after <- waitText(root, "effort", "xhigh")
            yield assertTrue(after == "xhigh")
          }
        yield result
        end for
      },
      test("mode arrows move the highlight and Enter picks it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "mode")
              _     <- root.button("mode").click
              _     <- waitPresent(root, "mode-plan")
              _     <- root.textarea("draft").press("ArrowDown")
              _     <- root.textarea("draft").press("Enter")
              after <- waitText(root, "mode", "Plan")
            yield assertTrue(after.contains("Plan"))
          }
        yield result
        end for
      },
      test("effort chip opens a menu and picking one updates the label") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "effort")
              before <- root.button("effort").innerText
              _      <- root.button("effort").click
              _      <- waitPresent(root, "effort-menu")
              _      <- root.button("effort-xhigh").click
              _      <- waitGone(root, "effort-menu")
              after  <- waitText(root, "effort", "xhigh")
              model  <- root.button("model").innerText
            yield assertTrue(before == "high", after == "xhigh", model == "Grok 4.6")
          }
        yield result
        end for
      },
      test("slash effort opens the effort menu") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "slash-effort")
              _ <- root.button("slash-effort").click
              _ <- waitPresent(root, "effort-menu")
              _ <- waitPresent(root, "effort-high")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("picking an effort from slash updates the effort chip") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "effort")
              _     <- root.textarea("draft").fill("/effort")
              _     <- root.button("send").click
              _     <- waitPresent(root, "effort-menu")
              _     <- root.button("effort-xhigh").click
              _     <- waitGone(root, "effort-menu")
              after <- waitText(root, "effort", "xhigh")
            yield assertTrue(after == "xhigh")
          }
        yield result
        end for
      },
      test("slash effort on a model without reasoning toasts") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "model")
              _      <- root.button("model").click
              _      <- waitPresent(root, "model-menu")
              _      <- root.button("model-grok-code-fast-1").click
              _      <- waitGone(root, "model-menu")
              _      <- waitText(root, "model", "Grok Code Fast")
              _      <- root.textarea("draft").fill("/effort high")
              _      <- root.button("send").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status.contains("does not support"))
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
      test("rewind lists prompts and confirm keeps the earlier turn") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "first prompt"))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
                bridge.push(HostMsg.UserMessage("t2", "second prompt"))
                bridge.push(HostMsg.TurnEnd("t2", "end_turn"))
                bridge.push(HostMsg.RewindList(List(RewindPoint(0, "first prompt"), RewindPoint(1, "second prompt"))))
              }
              _     <- waitPresent(root, "rewind-0")
              _     <- root.button("rewind-0").click
              _     <- waitPresent(root, "rewind-confirm")
              _     <- root.button("rewind-yes").click
              _     <- waitGone(root, "rewind-confirm")
              _     <- waitGone(root, "user-t2")
              first <- waitPresent(root, "user-t1") *> root.getByTestId("user-t1").innerText
            yield assertTrue(first.contains("first prompt"))
          }
        yield result
        end for
      },
      test("slash rewind with no turns toasts") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _      <- root.textarea("draft").fill("/rewind")
              _      <- root.button("send").click
              status <- waitPresent(root, "status") *> root.getByTestId("status").innerText
            yield assertTrue(status.contains("Nothing to rewind"))
          }
        yield result
        end for
      },
      test("slash rewind with turns opens the picker from local prompts") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "first prompt"))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
                bridge.push(HostMsg.UserMessage("t2", "second prompt"))
                bridge.push(HostMsg.TurnEnd("t2", "end_turn"))
              }
              _ <- waitPresent(root, "user-t2")
              _ <- root.textarea("draft").fill("/rewind")
              _ <- root.button("send").click
              _ <- waitPresent(root, "rewind-0")
              _ <- waitPresent(root, "rewind-1")
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
      test("expanding a finished execute tool still shows command and stdout after the live tail") {
        val bridge  = PushBridge()
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stream  = "one\ntwo\nthree\nbeard-terminal-probe"
        val stdout  = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "probe"))
                bridge.push(
                  HostMsg.ToolCall(
                    "t1",
                    ToolRow(
                      ToolCallId("term-1"),
                      "run_terminal_command",
                      ToolKind.Execute,
                      ToolStatus.InProgress,
                      input = Some(command),
                    ),
                  )
                )
                bridge.push(HostMsg.ToolChunk("t1", ToolCallId("term-1"), stream, snapshot = true))
              }
              tail <- waitPresent(root, "tool-tail-term-1") *>
                root.getByTestId("tool-tail-term-1").innerText
              _ <- ZIO.succeed {
                bridge.push(
                  HostMsg.ToolCall(
                    "t1",
                    ToolRow(
                      ToolCallId("term-1"),
                      "Tool",
                      ToolKind.Other,
                      ToolStatus.Completed,
                    ),
                  )
                )
                bridge.push(HostMsg.ToolChunk("t1", ToolCallId("term-1"), stdout, snapshot = true))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitGone(root, "tool-tail-term-1")
              details = root.element.queryHtml("""[data-testid="tool-term-1"]""")
              _       <- ZIO.succeed { details.setAttribute("open", "") }
              summary <- ZIO.succeed(details.queryHtml("summary").innerText)
              input   <- waitPresent(root, "tool-input-term-1") *>
                root.getByTestId("tool-input-term-1").innerText
              output <- waitPresent(root, "tool-output-term-1") *>
                root.getByTestId("tool-output-term-1").innerText
            yield assertTrue(
              tail.contains("beard-terminal-probe"),
              !tail.contains("uname -s"),
              summary.contains("run_terminal_command"),
              !summary.contains("Darwin"),
              input.contains("echo beard-terminal-probe"),
              input.contains("uname -s"),
              output.contains("beard-terminal-probe"),
              output.contains("Darwin"),
            )
          }
        yield result
        end for
      },
      test("a running execute tool's live tail updates as stdout grows") {
        val bridge                                                    = PushBridge()
        def call(status: ToolStatus = ToolStatus.InProgress): HostMsg =
          HostMsg.ToolCall(
            "t1",
            ToolRow(
              ToolCallId("term-1"),
              "run_terminal_command",
              ToolKind.Execute,
              status,
              input = Some("echo beard-terminal-probe"),
            ),
          )
        def chunk(out: String): HostMsg = HostMsg.ToolChunk("t1", ToolCallId("term-1"), out)
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "probe"))
                bridge.push(call())
                bridge.push(chunk((1 to 6).map(i => s"line-$i").mkString("\n")))
              }
              first <- waitContains(root, "tool-tail-term-1", "line-6")
              _     <- ZIO.succeed {
                bridge.push(chunk("\nline-7\nline-8"))
              }
              grown <- waitContains(root, "tool-tail-term-1", "line-8")
              _     <- ZIO.succeed {
                bridge.push(call(ToolStatus.Completed))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitGone(root, "tool-tail-term-1")
              details = root.element.queryHtml("""[data-testid="tool-term-1"]""")
              _      <- ZIO.succeed { details.setAttribute("open", "") }
              output <- waitContains(root, "tool-output-term-1", "line-1")
            yield assertTrue(
              first.contains("line-4"),
              !first.contains("line-1"),
              grown.contains("line-6"),
              grown.contains("line-8"),
              !grown.contains("line-5"),
              output.contains("line-8"),
            )
          }
        yield result
        end for
      },
      test("a running execute tool shows a live output tail until expanded") {
        val bridge  = PushBridge()
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stdout  = "one\ntwo\nthree\nbeard-terminal-probe\nDarwin\n"
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "probe"))
                bridge.push(
                  HostMsg.ToolCall(
                    "t1",
                    ToolRow(
                      ToolCallId("term-1"),
                      "run_terminal_command",
                      ToolKind.Execute,
                      ToolStatus.InProgress,
                      input = Some(command),
                    ),
                  )
                )
                bridge.push(HostMsg.ToolChunk("t1", ToolCallId("term-1"), stdout))
              }
              tail <- waitPresent(root, "tool-tail-term-1") *>
                root.getByTestId("tool-tail-term-1").innerText
              activity <- waitPresent(root, "activity-detail") *>
                root.getByTestId("activity-detail").innerText
              details = root.element.queryHtml("""[data-testid="tool-term-1"]""")
              _     <- ZIO.succeed { details.setAttribute("open", "") }
              input <- waitPresent(root, "tool-input-term-1") *>
                root.getByTestId("tool-input-term-1").innerText
              output <- waitPresent(root, "tool-output-term-1") *>
                root.getByTestId("tool-output-term-1").innerText
            yield assertTrue(
              tail.contains("Darwin"),
              !tail.contains("echo beard-terminal-probe"),
              activity.contains("Darwin"),
              input.contains("echo beard-terminal-probe"),
              output.contains("one"),
              output.contains("Darwin"),
            )
          }
        yield result
        end for
      },
      test("execute tool details keep the command and its output") {
        val bridge  = PushBridge()
        val command = "echo beard-terminal-probe\npwd\nuname -s"
        val stdout  = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "probe"))
                bridge.push(
                  HostMsg.ToolCall(
                    "t1",
                    ToolRow(
                      ToolCallId("term-1"),
                      "run_terminal_command",
                      ToolKind.Execute,
                      ToolStatus.Completed,
                      input = Some(command),
                    ),
                  )
                )
                bridge.push(HostMsg.ToolChunk("t1", ToolCallId("term-1"), stdout))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitPresent(root, "tool-term-1")
              details = root.element.queryHtml("""[data-testid="tool-term-1"]""")
              _       <- ZIO.succeed { details.setAttribute("open", "") }
              summary <- ZIO.succeed(details.queryHtml("summary").innerText)
              input   <- waitPresent(root, "tool-input-term-1") *>
                root.getByTestId("tool-input-term-1").innerText
              output <- waitPresent(root, "tool-output-term-1") *>
                root.getByTestId("tool-output-term-1").innerText
            yield assertTrue(
              summary.contains("run_terminal_command"),
              !summary.contains("Darwin"),
              input.contains("echo beard-terminal-probe"),
              input.contains("uname -s"),
              output.contains("beard-terminal-probe"),
              output.contains("Darwin"),
            )
          }
        yield result
        end for
      },
      test("thought details keep the full thinking body") {
        val bridge = PushBridge()
        val line   = ("thinking " * 40).trim
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "hello"))
                bridge.push(HostMsg.ThoughtChunk("t1", line))
                bridge.push(HostMsg.AgentChunk("t1", "ok"))
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _       <- waitPresent(root, "thought-t1")
              thought <- ZIO.succeed(
                root.element.queryHtml("""[data-testid="thought-t1"]""")
              )
              pre <- ZIO.succeed(thought.queryHtml("pre"))
              cls = Option(pre.getAttribute("class")).getOrElse("")
              text <- ZIO.succeed(thought.innerText)
            yield assertTrue(cls.contains("ThoughtBody"), text.contains("thinking"))
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
      test("palette scene lists MCP Servers and picking it opens the MCP pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Palette)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "palette")
              _   <- waitPresent(root, "palette-mcps")
              _   <- root.button("palette-mcps").click
              _   <- waitPresent(root, "mcps")
              _   <- waitPresent(root, "mcp-metals")
              _   <- waitGone(root, "palette")
              off <- waitPresent(root, "mcp-toggle-atlassian") *>
                root.button("mcp-toggle-atlassian").innerText
              _  <- root.button("mcp-toggle-atlassian").click
              on <- waitContains(root, "mcp-toggle-atlassian", "On")
            yield assertTrue(off.contains("Off"), on.contains("On"))
          }
        yield result
        end for
      },
      test("empty draft question-mark opens the command palette") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "draft")
              _      <- root.textarea("draft").press("?")
              _      <- waitPresent(root, "palette")
              _      <- waitPresent(root, "palette-mcps")
              filter <- root.input("palette-filter").value
            yield assertTrue(filter == "")
          }
        yield result
        end for
      },
      test("palette Enter from the composer opens MCP servers") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "draft")
              _ <- root.textarea("draft").press("?")
              _ <- waitPresent(root, "palette-mcps")
              _ <- root.textarea("draft").press("Enter")
              _ <- waitPresent(root, "mcps")
              _ <- waitGone(root, "palette")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("palette arrows from the composer move the highlight") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "draft")
              _ <- root.textarea("draft").press("?")
              _ <- waitPresent(root, "palette-new")
              _ <- root.textarea("draft").press("ArrowDown")
              _ <- root.textarea("draft").press("Enter")
              _ <- waitGone(root, "palette")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("palette Enter from the filter opens MCP servers") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Palette)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "palette-mcps")
              _ <- root.input("palette-filter").press("Enter")
              _ <- waitPresent(root, "mcps")
              _ <- waitGone(root, "palette")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("palette arrows from the filter move the highlight") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Palette)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "palette-new")
              _ <- root.input("palette-filter").press("ArrowDown")
              _ <- root.input("palette-filter").press("Enter")
              _ <- waitGone(root, "palette")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("palette filter input narrows the list") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Palette)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "palette-mcps")
              _ <- waitPresent(root, "palette-new")
              _ <- root.input("palette-filter").fill("todo")
              _ <- waitPresent(root, "palette-todos")
              _ <- waitGone(root, "palette-mcps")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash /mcps opens the MCP pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "slash-mcps")
              _      <- root.button("slash-mcps").click
              _      <- waitPresent(root, "mcps")
              metals <- waitPresent(root, "mcp-metals") *>
                root.getByTestId("mcp-metals").innerText
            yield assertTrue(metals.contains("metals"))
          }
        yield result
        end for
      },
      test("slash /session-info opens the session pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "slash-session-info")
              _ <- root.button("slash-session-info").click
              _ <- waitPresent(root, "session-info")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash /context opens the context pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "slash-context")
              _ <- root.button("slash-context").click
              _ <- waitPresent(root, "context")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("palette lists Session info and Context") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Palette)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "palette-session-info")
              _ <- waitPresent(root, "palette-context")
              _ <- root.button("palette-context").click
              _ <- waitPresent(root, "context")
              _ <- waitGone(root, "palette")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash rename prefills the composer") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "slash-rename")
              _     <- root.button("slash-rename").click
              draft <- waitValue(root, "/rename ")
            yield assertTrue(draft == "/rename ")
          }
        yield result
        end for
      },
      test("picker Delete then Confirm removes the row") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Resume)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "sessions")
              _ <- root.button("sessions").click
              _ <- waitPresent(root, "session-picker")
              _ <- root.button("session-delete-disk-2").click
              _ <- waitPresent(root, "delete-confirm")
              _ <- root.button("delete-yes").click
              _ <- waitGone(root, "session-disk-2")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("rename from the composer updates the session chip") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Resume)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "sessions")
              _     <- root.button("sessions").click
              _     <- waitPresent(root, "session-picker")
              _     <- root.button("session-disk-1").click
              _     <- waitPresent(root, "user-resume-turn")
              _     <- root.textarea("draft").fill("/rename Beard plan")
              _     <- root.button("send").click
              label <- waitText(root, "sessions", "Beard plan")
            yield assertTrue(label.contains("Beard plan"))
          }
        yield result
        end for
      },
      test("esc on a running turn toasts Ctrl+C and does not stop") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- ZIO.succeed(bridge.push(HostMsg.UserMessage(TurnId("t-run"), "go")))
              _     <- waitPresent(root, "activity")
              _     <- root.textarea("draft").press("Escape")
              toast <- waitPresent(root, "status") *> root.getByTestId("status").innerText
              still <- waitPresent(root, "activity")
            yield assertTrue(toast.contains("Ctrl+C"), still)
          }
        yield result
        end for
      },
      test("child scene opens a framed transcript") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Child)
          result <- withMounted(ui) { root =>
            for frame <- waitPresent(root, "child-frame") *> root.getByTestId("child-transcript").innerText
            yield assertTrue(frame.contains("Child is working"))
          }
        yield result
      },
      test("child steer adds a user line") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Child)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "child-draft")
              _     <- root.textarea("child-draft").fill("steer note")
              _     <- root.button("child-send").click
              _     <- waitPresent(root, "child-turn-child-steer")
              frame <- root.getByTestId("child-transcript").innerText
            yield assertTrue(frame.contains("Heard: steer note"))
          }
        yield result
        end for
      },
      test("cancel scene lists 1-4 keep/stop choices") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "cancel-turn")
              one  <- waitPresent(root, "cancel-1") *> root.getByTestId("cancel-1").innerText
              four <- root.getByTestId("cancel-4").innerText
            yield assertTrue(one.contains("Stop running"), four.contains("Always continue"))
          }
        yield result
        end for
      },
      test("agents scene has agents and personas") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Agents)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "agents")
              _ <- waitPresent(root, "agent-explore")
              _ <- root.button("agents-tab-personas").click
              _ <- waitPresent(root, "persona-concise")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("plan-view, doctor, theme, workflows, dashboard, btw, images, voice scenes mount") {
        val bridge                      = PreviewBridge()
        def scene(s: Scene, id: String) =
          ChatApp.component(bridge, None, s).flatMap { ui =>
            withMounted(ui)(root => waitPresent(root, id))
          }
        for
          plan  <- scene(Scene.PlanView, "plan-view")
          doc   <- scene(Scene.Doctor, "doctor")
          theme <- scene(Scene.Theme, "theme")
          wf    <- scene(Scene.Workflows, "workflows")
          dash  <- scene(Scene.Dashboard, "dashboard")
          btw   <- scene(Scene.Btw, "btw")
          img   <- scene(Scene.Images, "image-0")
          voice <- scene(Scene.Voice, "voice")
        yield assertTrue(plan, doc, theme, wf, dash, btw, img, voice)
        end for
      },
      test("theme click sets data-theme tokyonight") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Theme)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "theme-tokyonight")
              _ <- root.button("theme-tokyonight").click
              _ <- waitGone(root, "theme")
              attr = Option(root.element.getAttribute("data-theme"))
              html = Option(ascent.dom.window.document.documentElement.getAttribute("data-theme"))
            yield assertTrue(attr.contains("tokyonight") || html.contains("tokyonight"))
          }
        yield result
        end for
      },
      test("compact scene marks data-compact") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Compact)
          result <- withMounted(ui) { root =>
            waitSelector(root, """[data-compact="true"]""").map(assertTrue(_))
          }
        yield result
      },
      test("image chip can be removed") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Images)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "image-0")
              _ <- root.button("image-remove-image-0").click
              _ <- waitGone(root, "image-0")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("resume picker has Restore code") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "sessions")
              _   <- root.button("sessions").click
              _   <- waitPresent(root, "session-picker")
              box <- waitPresent(root, "resume-restore")
            yield assertTrue(box)
          }
        yield result
        end for
      },
      test("vim mode selects a turn") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "turn-t1")
              _   <- ZIO.succeed(bridge.push(HostMsg.UiPrefs("vscode", compact = false, vim = true)))
              _   <- waitSelector(root, """[data-vim="true"]""")
              _   <- root.getByTestId("turn-t1").click
              sel <- waitSelector(root, """[data-selected="true"]""")
            yield assertTrue(sel)
          }
        yield result
        end for
      },
      test("idle Esc Esc stashes a draft and shows the caption") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "draft")
              _     <- root.textarea("draft").fill("keep me")
              _     <- root.textarea("draft").press("Escape")
              toast <- waitContains(root, "status", CancelTurn.ClearHint)
              _     <- root.textarea("draft").press("Escape")
              _     <- waitValue(root, "")
              cap   <- waitPresent(root, "stash") *> root.getByTestId("stash").innerText
            yield assertTrue(toast.contains("press again"), cap.contains(DraftStash.Caption))
          }
        yield result
        end for
      },
      test("Ctrl+S stashes and restores a draft") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "draft")
              _   <- root.textarea("draft").fill("stashed draft")
              _   <- ZIO.succeed(fireCtrlS(root, "draft"))
              _   <- waitValue(root, "")
              _   <- waitPresent(root, "stash")
              _   <- ZIO.succeed(fireCtrlS(root, "draft"))
              got <- waitValue(root, "stashed draft")
            yield assertTrue(got == "stashed draft")
          }
        yield result
        end for
      },
      test("Ctrl+S stash restores after send") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "draft")
              _   <- root.textarea("draft").fill("stashed draft")
              _   <- ZIO.succeed(fireCtrlS(root, "draft"))
              _   <- waitValue(root, "")
              _   <- root.textarea("draft").fill("hello")
              _   <- root.button("send").click
              _   <- waitPresent(root, "user-preview-turn")
              got <- waitValue(root, "stashed draft")
            yield assertTrue(got == "stashed draft")
          }
        yield result
        end for
      },
      test("Esc Esc stash does not restore after send") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "draft")
              _    <- root.textarea("draft").fill("gone")
              _    <- root.textarea("draft").press("Escape")
              _    <- waitContains(root, "status", CancelTurn.ClearHint)
              _    <- root.textarea("draft").press("Escape")
              _    <- waitValue(root, "")
              _    <- root.textarea("draft").fill("hello")
              _    <- root.button("send").click
              _    <- waitPresent(root, "user-preview-turn")
              left <- root.textarea("draft").value
            yield assertTrue(left.isEmpty)
          }
        yield result
        end for
      },
      test("voice scene Esc disarms listening") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Voice)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "voice")
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "voice")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("slash voice toasts when the speech constructor is missing") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "draft")
              _     <- root.textarea("draft").fill("/voice")
              _     <- root.button("send").click
              toast <- waitContains(root, "status", VoiceCapture.NoDevice)
            yield assertTrue(toast.contains(VoiceCapture.NoDevice))
          }
        yield result
        end for
      },
      test("cancel Esc keeps the turn running") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "cancel-turn")
              _     <- root.textarea("draft").press("Escape")
              _     <- waitGone(root, "cancel-turn")
              still <- waitPresent(root, "activity")
            yield assertTrue(still)
          }
        yield result
        end for
      },
      test("cancel 1 stops running subagents") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "cancel-1")
              _ <- root.textarea("draft").press("1")
              _ <- waitGone(root, "cancel-turn")
            yield assertTrue(true)
          }
        yield result
        end for
      },
      test("theme arrows preview then Esc reverts") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Theme)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "theme")
              _ <- root.textarea("draft").press("ArrowDown")
              previewed = Option(ascent.dom.window.document.documentElement.getAttribute("data-theme"))
              _ <- root.textarea("draft").press("Escape")
              _ <- waitGone(root, "theme")
              restored = Option(ascent.dom.window.document.documentElement.getAttribute("data-theme"))
            yield assertTrue(previewed.contains("auto"), restored.contains("vscode"))
          }
        yield result
        end for
      },
      test("doctor fix lists CLI repair") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "draft")
              _ <- root.textarea("draft").fill("/doctor fix")
              _ <- root.button("send").click
              _ <- waitPresent(root, "doctor")
              _ <- ZIO.succeed(
                bridge.push(
                  HostMsg.DoctorReport(
                    Doctor.collect(None, None, true, false, true, true, true, "/repo", 0, "ready", None)
                  )
                )
              )
              row <- waitPresent(root, "doctor-fix-cli") *> root.getByTestId("doctor-fix-cli").innerText
            yield assertTrue(row.contains("CLI"))
          }
        yield result
        end for
      },
      test("child queue checkbox is present") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Child)
          result <- withMounted(ui) { root =>
            waitPresent(root, "child-queue").map(assertTrue(_))
          }
        yield result
      },
      test("FileReader facade reads a constructed image file") {
        val file = new groksbeard.facade.File(
          scala.scalajs.js.Array[scala.scalajs.js.Any]("x"),
          "paste.png",
          groksbeard.facade.FilePropertyBag("image/png"),
        )
        ChatApp.readImage(file).map { got =>
          assertTrue(got.exists { (mime, data, name) =>
            mime == "image/png" && name == "paste.png" && data.nonEmpty
          })
        }
      },
      test("Stop on a running turn ends activity") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- ZIO.succeed(bridge.push(HostMsg.UserMessage(TurnId("t-run"), "go")))
              _     <- waitPresent(root, "activity")
              label <- waitText(root, "send", "Stop")
              _     <- root.button("send").click
              _     <- waitGone(root, "activity")
            yield assertTrue(label == "Stop")
          }
        yield result
        end for
      },
      test("Ctrl+C clears a draft then a second Ctrl+C stops the turn") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- ZIO.succeed(bridge.push(HostMsg.UserMessage(TurnId("t-run"), "go")))
              _     <- waitPresent(root, "activity")
              _     <- root.textarea("draft").fill("note")
              _     <- ZIO.succeed(fireCtrlC(root, "draft"))
              empty <- waitValue(root, "")
              still <- waitPresent(root, "activity")
              _     <- ZIO.succeed(fireCtrlC(root, "draft"))
              _     <- waitGone(root, "activity")
            yield assertTrue(empty.isEmpty, still)
          }
        yield result
        end for
      },
      test("idle Esc Esc on an empty draft opens rewind") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "draft")
              _     <- root.textarea("draft").press("Escape")
              _     <- root.textarea("draft").press("Escape")
              shown <- waitPresent(root, "rewind")
            yield assertTrue(shown)
          }
        yield result
        end for
      },
      test("vim j and k move the selected turn") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "turn-t1")
              _    <- ZIO.succeed(bridge.push(HostMsg.UiPrefs("vscode", compact = false, vim = true)))
              _    <- waitSelector(root, """[data-vim="true"]""")
              _    <- ZIO.succeed(bridge.push(HostMsg.UserMessage(TurnId("t2"), "second")))
              _    <- waitPresent(root, "turn-t2")
              _    <- root.getByTestId("turn-t1").click
              _    <- waitSelector(root, """[data-testid="turn-t1"][data-selected="true"]""")
              _    <- ZIO.succeed(fireKey(root, "turn-t1", "j", "KeyJ"))
              next <- waitSelector(root, """[data-testid="turn-t2"][data-selected="true"]""")
              _    <- ZIO.succeed(fireKey(root, "turn-t2", "k", "KeyK"))
              prev <- waitSelector(root, """[data-testid="turn-t1"][data-selected="true"]""")
            yield assertTrue(next, prev)
          }
        yield result
        end for
      },
      test("vim y on a turn does not type into the draft") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _   <- waitPresent(root, "turn-t1")
              _   <- ZIO.succeed(bridge.push(HostMsg.UiPrefs("vscode", compact = false, vim = true)))
              _   <- waitSelector(root, """[data-vim="true"]""")
              _   <- root.getByTestId("turn-t1").click
              _   <- ZIO.succeed(fireKey(root, "turn-t1", "y", "KeyY"))
              got <- root.textarea("draft").value
            yield assertTrue(got.isEmpty)
          }
        yield result
        end for
      },
      test("vim on does not steal j from the focused draft") {
        val bridge = PushBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Transcript)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "turn-t1")
              _     <- ZIO.succeed(bridge.push(HostMsg.UiPrefs("vscode", compact = false, vim = true)))
              _     <- waitSelector(root, """[data-vim="true"]""")
              _     <- ZIO.succeed(bridge.push(HostMsg.UserMessage(TurnId("t2"), "second")))
              _     <- waitPresent(root, "turn-t2")
              _     <- root.getByTestId("turn-t1").click
              _     <- waitSelector(root, """[data-testid="turn-t1"][data-selected="true"]""")
              _     <- root.textarea("draft").click
              _     <- ZIO.succeed(fireKey(root, "draft", "j", "KeyJ"))
              still <- waitSelector(root, """[data-testid="turn-t1"][data-selected="true"]""")
            yield assertTrue(still)
          }
        yield result
        end for
      },
      test("cancel 2 keeps subagents") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "cancel-2")
              _     <- root.textarea("draft").press("2")
              _     <- waitGone(root, "cancel-turn")
              _     <- waitGone(root, "activity")
              still <- waitPresent(root, "subagent-sub-1")
            yield assertTrue(still)
          }
        yield result
        end for
      },
      test("cancel 3 persists always stop") {
        val bridge = PersistSpy()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _ <- waitPresent(root, "cancel-3")
              _ <- root.textarea("draft").press("3")
              _ <- waitGone(root, "cancel-turn")
            yield assertTrue(
              bridge.configs.contains(("ui", "cancel_subagents_on_turn_cancel", "always_stop"))
            )
          }
        yield result
        end for
      },
      test("cancel 4 persists always continue") {
        val bridge = PersistSpy()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Cancel)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "cancel-4")
              _     <- root.textarea("draft").press("4")
              _     <- waitGone(root, "cancel-turn")
              still <- waitPresent(root, "subagent-sub-1")
            yield assertTrue(
              still,
              bridge.configs.contains(("ui", "cancel_subagents_on_turn_cancel", "always_continue")),
            )
          }
        yield result
        end for
      },
      test("slash view-plan opens the plan overlay") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- root.textarea("draft").fill("/view-plan")
              _     <- root.button("send").click
              shown <- waitPresent(root, "plan-view")
            yield assertTrue(shown)
          }
        yield result
        end for
      },
      test("workflow overlay p pauses the selected run without Stop") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Workflows)
          result <- withMounted(ui) { root =>
            for
              _      <- waitPresent(root, "workflow-review-changes")
              _      <- root.getByTestId("workflow-review-changes").click
              _      <- root.textarea("draft").press("p")
              paused <- waitContains(root, "workflow-review-changes", "paused")
            yield assertTrue(
              paused.contains("paused"),
              bridge.sent.exists {
                case WebviewMsg.WorkflowControl("pause", "review-changes") => true
                case _                                                     => false
              },
              !bridge.sent.exists {
                case WebviewMsg.Cancel => true
                case _                 => false
              },
            )
          }
        yield result
        end for
      },
      test("dashboard click of the current session keeps the transcript") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Dashboard)
          result <- withMounted(ui) { root =>
            for
              _    <- waitPresent(root, "user-t1")
              _    <- root.button("dash-disk-1").click
              kept <- waitPresent(root, "user-t1")
              _    <- waitGone(root, "user-resume-turn")
            yield assertTrue(
              kept,
              bridge.sent.exists {
                case WebviewMsg.ResumeSession(id, _, true) => id.value == "disk-1"
                case _                                     => false
              },
            )
          }
        yield result
        end for
      },
      test("slash workflow runs opens the workflows pane") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _     <- root.textarea("draft").fill("/workflow runs")
              _     <- root.button("send").click
              shown <- waitPresent(root, "workflows")
            yield assertTrue(shown)
          }
        yield result
        end for
      },
      test("slash config-agents opens agents") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _   <- root.textarea("draft").fill("/config-agents")
              _   <- root.button("send").click
              _   <- waitPresent(root, "agents")
              row <- waitPresent(root, "agent-explore")
            yield assertTrue(row)
          }
        yield result
        end for
      },
      test("slash personas opens the personas tab") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _   <- root.textarea("draft").fill("/personas")
              _   <- root.button("send").click
              row <- waitPresent(root, "persona-concise")
            yield assertTrue(row)
          }
        yield result
        end for
      },
      test("slash minimal then fullscreen toggles compact chrome") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _   <- root.textarea("draft").fill("/minimal")
              _   <- root.button("send").click
              on  <- waitSelector(root, """[data-compact="true"]""")
              _   <- root.textarea("draft").fill("/fullscreen")
              _   <- root.button("send").click
              off <- waitSelector(root, """[data-compact="false"]""")
            yield assertTrue(on, off)
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
        Option(e.getAttribute("data-testid")).filter { s =>
          s != null && s.startsWith("session-") && !s.startsWith("session-delete-")
        }
      }.flatten
    }

  private def waitSelector(root: AscentRoot, sel: String)(using Trace): IO[Throwable, Boolean] =
    def loop: IO[Throwable, Boolean] =
      ZIO.succeed(Option(root.element.querySelector(sel))).flatMap {
        case Some(_) => ZIO.succeed(true)
        case None    => ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $sel"))(5.seconds)

  private def waitContains(root: AscentRoot, testId: String, needle: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      root.getByTestId(testId).innerText.flatMap { t =>
        if t.contains(needle) then ZIO.succeed(t) else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testId to contain $needle"))(5.seconds)

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

  private def waitScrollTop(root: AscentRoot, sel: String, min: Double)(using Trace): IO[Throwable, Double] =
    def loop: IO[Throwable, Double] =
      ZIO.succeed(root.element.queryHtml(sel).scrollTop).flatMap { top =>
        if top > min then ZIO.succeed(top) else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $sel scrollTop > $min"))(5.seconds)

  private def fireScroll(el: ascent.dom.HTMLElement): Unit =
    val _ = el.dispatchEvent(new ascent.dom.Event("scroll"))

  private def fireCtrlT(root: AscentRoot, testId: String): Unit =
    fireCtrlKey(root, testId, "t", "KeyT")

  private def fireCtrlG(root: AscentRoot, testId: String): Unit =
    fireCtrlKey(root, testId, "g", "KeyG")

  private def fireCtrlS(root: AscentRoot, testId: String): Unit =
    fireCtrlKey(root, testId, "s", "KeyS")

  private def fireCtrlC(root: AscentRoot, testId: String): Unit =
    fireCtrlKey(root, testId, "c", "KeyC")

  private def fireCtrlKey(root: AscentRoot, testId: String, key: String, code: String): Unit =
    val el = root.element.queryHtml(s"""[data-testid="$testId"]""")
    val _  = el.dispatchEvent(JsDom.keyDown(key, code, ctrl = true))

  private def fireKey(root: AscentRoot, testId: String, key: String, code: String, shift: Boolean = false): Unit =
    val el = root.element.queryHtml(s"""[data-testid="$testId"]""")
    val _  = el.dispatchEvent(JsDom.keyDown(key, code, shift = shift))
end ChatChromeSpec

/** Pushes HostMsg the way EventSource onmessage does: many callbacks, no backpressure. */
final class PushBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  def post(msg: WebviewMsg): Unit       =
    msg match
      case WebviewMsg.RewindTo(index)                            => listener(HostMsg.Rewound(index))
      case WebviewMsg.Cancel | WebviewMsg.CancelTurnChoice(_, _) =>
        listener(HostMsg.TurnEnd(TurnId("t-run"), StopReason.Cancelled))
      case _ => ()
  def onHost(f: HostMsg => Unit): Unit = listener = f
  def push(msg: HostMsg): Unit         = listener(msg)
end PushBridge

final class PersistSpy extends HostBridge:
  private val inner                           = PreviewBridge()
  var configs: List[(String, String, String)] = Nil
  def post(msg: WebviewMsg): Unit             =
    msg match
      case WebviewMsg.PersistConfig(t, k, v) => configs = configs :+ (t, k, v)
      case _                                 => ()
    inner.post(msg)
  def onHost(f: HostMsg => Unit): Unit = inner.onHost(f)

/** ResumeSession holds the snapshot until [[completeResume]], so tests can see loading chrome. */
final class GatedResumeBridge extends HostBridge:
  private var listener: HostMsg => Unit  = _ => ()
  private var pending: Option[SessionId] = None
  private val sessions                   = List(
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
      case WebviewMsg.ResumeSession(id, _, _) =>
        pending = Some(id)
        val title = sessions.find(_.id == id).map(_.title).getOrElse(id.value)
        emit(HostMsg.ClearTranscript)
        emit(HostMsg.SessionMeta(id, title, ModeId.Normal))
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
