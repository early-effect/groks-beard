package groksbeard.ui

import ascent.History
import ascent.chekhov.AscentChekhov.withMounted
import ascent.chekhov.AscentRoot
import ascent.chekhov.value
import groksbeard.core.*
import scala.scalajs.js
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
      test("slash arrows move the highlight and Enter picks it") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Slash)
          result <- withMounted(ui) { root =>
            for
              _     <- waitPresent(root, "slash-compact")
              _     <- waitPresent(root, "slash-always-approve")
              _     <- root.textarea("draft").press("ArrowDown")
              _     <- root.textarea("draft").press("Enter")
              draft <- waitValue(root, "/always-approve ")
            yield assertTrue(draft == "/always-approve ")
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
                root.element.querySelector("""[data-testid="transcript"]""").asInstanceOf[ascent.dom.HTMLElement]
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
      test("todos scene lists entries and Hide collapses them") {
        val bridge = PreviewBridge()
        for
          ui     <- ChatApp.component(bridge, None, Scene.Todos)
          result <- withMounted(ui) { root =>
            for
              head <- waitPresent(root, "todos") *> root.getByTestId("todos").innerText
              row  <- waitPresent(root, "todo-2") *> root.getByTestId("todo-2").innerText
              done <- waitPresent(root, "todo-1") *> root.getByTestId("todo-1").innerText
              _    <- root.button("todos-toggle").click
              _    <- waitGone(root, "todos-list")
            yield assertTrue(
              head.contains("Todos 1/3"),
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
              _ <- waitGone(root, "todos-list")
              _ <- ZIO.succeed(fireCtrlT(root, "draft"))
              _ <- waitPresent(root, "todos-list")
            yield assertTrue(true)
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
            yield assertTrue(head.contains("Todos 0/2"), row.contains("Checkout branch"))
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
                  HostMsg.ToolGroup(
                    "t1",
                    List(
                      ToolRow(
                        ToolCallId("term-1"),
                        "run_terminal_command",
                        ToolKind.Execute,
                        ToolStatus.InProgress,
                        input = Some(command),
                        output = Some(stream),
                      )
                    ),
                  )
                )
              }
              tail <- waitPresent(root, "tool-tail-term-1") *>
                root.getByTestId("tool-tail-term-1").innerText
              _ <- ZIO.succeed {
                bridge.push(
                  HostMsg.ToolGroup(
                    "t1",
                    List(
                      ToolRow(
                        ToolCallId("term-1"),
                        "Tool",
                        ToolKind.Other,
                        ToolStatus.Completed,
                        output = Some(stdout),
                      )
                    ),
                  )
                )
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitGone(root, "tool-tail-term-1")
              details = root.element
                .querySelector("""[data-testid="tool-term-1"]""")
                .asInstanceOf[ascent.dom.HTMLElement]
              _       <- ZIO.succeed { details.setAttribute("open", "") }
              summary <- ZIO.succeed(
                details.querySelector("summary").asInstanceOf[ascent.dom.HTMLElement].innerText
              )
              input <- waitPresent(root, "tool-input-term-1") *>
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
        val bridge                                                                = PushBridge()
        def row(out: String, status: ToolStatus = ToolStatus.InProgress): ToolRow =
          ToolRow(
            ToolCallId("term-1"),
            "run_terminal_command",
            ToolKind.Execute,
            status,
            input = Some("echo beard-terminal-probe"),
            output = Some(out),
          )
        for
          ui     <- ChatApp.component(bridge, None, Scene.Empty)
          result <- withMounted(ui) { root =>
            for
              _ <- ZIO.succeed {
                bridge.push(HostMsg.UserMessage("t1", "probe"))
                bridge.push(HostMsg.ToolGroup("t1", List(row((1 to 6).map(i => s"line-$i").mkString("\n")))))
              }
              first <- waitContains(root, "tool-tail-term-1", "line-6")
              _     <- ZIO.succeed {
                bridge.push(HostMsg.ToolGroup("t1", List(row((1 to 8).map(i => s"line-$i").mkString("\n")))))
              }
              grown <- waitContains(root, "tool-tail-term-1", "line-8")
              _     <- ZIO.succeed {
                bridge.push(
                  HostMsg.ToolGroup("t1", List(row((1 to 8).map(i => s"line-$i").mkString("\n"), ToolStatus.Completed)))
                )
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitGone(root, "tool-tail-term-1")
              details = root.element
                .querySelector("""[data-testid="tool-term-1"]""")
                .asInstanceOf[ascent.dom.HTMLElement]
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
                  HostMsg.ToolGroup(
                    "t1",
                    List(
                      ToolRow(
                        ToolCallId("term-1"),
                        "run_terminal_command",
                        ToolKind.Execute,
                        ToolStatus.InProgress,
                        input = Some(command),
                        output = Some(stdout),
                      )
                    ),
                  )
                )
              }
              tail <- waitPresent(root, "tool-tail-term-1") *>
                root.getByTestId("tool-tail-term-1").innerText
              activity <- waitPresent(root, "activity-detail") *>
                root.getByTestId("activity-detail").innerText
              details = root.element
                .querySelector("""[data-testid="tool-term-1"]""")
                .asInstanceOf[ascent.dom.HTMLElement]
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
                  HostMsg.ToolGroup(
                    "t1",
                    List(
                      ToolRow(
                        ToolCallId("term-1"),
                        "run_terminal_command",
                        ToolKind.Execute,
                        ToolStatus.Completed,
                        input = Some(command),
                        output = Some(stdout),
                      )
                    ),
                  )
                )
                bridge.push(HostMsg.TurnEnd("t1", "end_turn"))
              }
              _ <- waitPresent(root, "tool-term-1")
              details = root.element
                .querySelector("""[data-testid="tool-term-1"]""")
                .asInstanceOf[ascent.dom.HTMLElement]
              _       <- ZIO.succeed { details.setAttribute("open", "") }
              summary <- ZIO.succeed(
                details.querySelector("summary").asInstanceOf[ascent.dom.HTMLElement].innerText
              )
              input <- waitPresent(root, "tool-input-term-1") *>
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
                root.element.querySelector("""[data-testid="thought-t1"]""").asInstanceOf[ascent.dom.HTMLElement]
              )
              pre <- ZIO.succeed(thought.querySelector("pre").asInstanceOf[ascent.dom.HTMLElement])
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

  private def fireScroll(el: ascent.dom.HTMLElement): Unit =
    val ev = js.Dynamic.newInstance(js.Dynamic.global.Event)("scroll")
    val _  = el.dispatchEvent(ev.asInstanceOf[ascent.dom.Event])

  private def fireCtrlT(root: AscentRoot, testId: String): Unit =
    val el = root.element.querySelector(s"""[data-testid="$testId"]""")
    val ev = js.Dynamic.newInstance(js.Dynamic.global.KeyboardEvent)(
      "keydown",
      js.Dynamic.literal(key = "t", code = "KeyT", ctrlKey = true, bubbles = true, cancelable = true),
    )
    val _ = el.asInstanceOf[ascent.dom.HTMLElement].dispatchEvent(ev.asInstanceOf[ascent.dom.Event])
end ChatChromeSpec

/** Pushes HostMsg the way EventSource onmessage does: many callbacks, no backpressure. */
final class PushBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  def post(msg: WebviewMsg): Unit       =
    msg match
      case WebviewMsg.RewindTo(index) => listener(HostMsg.Rewound(index))
      case _                          => ()
  def onHost(f: HostMsg => Unit): Unit = listener = f
  def push(msg: HostMsg): Unit         = listener(msg)

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
      case WebviewMsg.ResumeSession(id) =>
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
