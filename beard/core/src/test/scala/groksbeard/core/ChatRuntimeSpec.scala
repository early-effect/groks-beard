package groksbeard.core

import java.util.concurrent.TimeUnit
import zio.*
import zio.json.*
import zio.test.*

object ChatRuntimeSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatRuntime")(
      test("ready posts sessionMeta and available commands") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        val tags = posted.toList.map {
          case HostMsg.Ready                => "ready"
          case _: HostMsg.SessionMeta       => "sessionMeta"
          case HostMsg.AvailableCommands(_) => "availableCommands"
          case _: HostMsg.Settings          => "settings"
          case other                        => other.toString
        }
        assertTrue(
          tags.contains("ready"),
          tags.contains("sessionMeta"),
          tags.contains("availableCommands"),
          posted.exists {
            case m: HostMsg.SessionMeta => m.modeId == "normal" && m.availableModes.exists(_.id == "plan")
            case _                      => false
          },
        )
      },
      test("send posts user, thought, agent, tool, then turnEnd") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("hello")
        val tags = posted.toList.collect {
          case HostMsg.UserMessage(_, text, _, _) => s"user:$text"
          case HostMsg.ThoughtChunk(_, text)      => s"thought:$text"
          case HostMsg.AgentChunk(_, text, _)     => s"agent:$text"
          case HostMsg.ToolGroup(_, tools)        => s"tool:${tools.map(_.title).mkString}"
          case HostMsg.TurnEnd(_, reason)         => s"end:$reason"
        }
        assertTrue(
          tags.head == "user:hello",
          tags.exists(_.startsWith("thought:")),
          tags.contains("agent:hello"),
          tags.exists(_.startsWith("tool:Edit")),
          tags.last == "end:end_turn",
        )
      },
      test("send folds through ChatModel into one completed turn") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("hello")
        val model = posted.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val turn  = model.turns.head
        assertTrue(
          turn.user.exists(_.text == "hello"),
          turn.thought.contains("Considering"),
          turn.agent == "hello",
          turn.tools.exists(_.title.contains("Edit")),
          turn.stopReason.contains("end_turn"),
          !ChatModel.turnIsRunning(model),
        )
      },
      test("send while a turn is running queues the follow-up until the turn ends") {
        val posted               = scala.collection.mutable.ListBuffer.empty[HostMsg]
        lazy val rt: ChatRuntime = ChatRuntime { msg =>
          posted += msg
          msg match
            case HostMsg.UserMessage(_, "hello", _, _) => rt.send("later")
            case _                                     => ()
        }
        rt.ready()
        posted.clear()
        rt.send("hello")
        val users = posted.toList.collect { case HostMsg.UserMessage(_, text, _, _) => text }
        assertTrue(
          posted.exists {
            case HostMsg.Queued(items) => items.map(_.text) == List("later")
            case _                     => false
          },
          users == List("hello", "later"),
        )
      },
      test("empty send is a no-op") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("   ")
        assertTrue(posted.isEmpty)
      },
      test("MCP AuthRequired unsticks a hung prompt and sends the parked follow-up") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, AcpTransport.fake(FakeAgent(hangPrompt = true)))
        rt.ready()
        posted.clear()
        rt.send("hello")
        rt.queue("later")
        rt.noteAgentLine(
          """ERROR worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer resource_metadata=\"https://mcp.atlassian.com/.well-known/oauth-protected-resource/v1/mcp/authv2\", error=\"invalid_token\"" })"""
        )
        assertTrue(
          posted.exists {
            case HostMsg.Error(message, _) => message.toLowerCase.contains("atlassian")
            case _                         => false
          },
          posted.exists {
            case HostMsg.TurnEnd(_, "cancelled") => true
            case _                               => false
          },
          posted.exists {
            case HostMsg.UserMessage(_, "later", _, _) => true
            case _                                     => false
          },
        )
      },
      test("queue parks the follow-up text without sending it") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.queue("later")
        assertTrue(
          posted.exists {
            case HostMsg.Queued(items) => items.map(_.text) == List("later")
            case _                     => false
          },
          !posted.exists {
            case _: HostMsg.UserMessage => true
            case _                      => false
          },
        )
      },
      test("cancel sends the next parked follow-up") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.queue("later")
        rt.cancel()
        assertTrue(
          posted.exists {
            case HostMsg.UserMessage(_, "later", _, _) => true
            case _                                     => false
          }
        )
      },
      test("send ingests the fake edit into Changes") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val disk   = Map("/tmp/Main.scala" -> "aaa\nobject Main\nccc\n")
        val rt     = ChatRuntime(posted += _, ports = ReviewPorts(readDisk = disk.get))
        rt.ready()
        posted.clear()
        rt.send("edit Main")
        val summary = posted.toList.collect { case c: HostMsg.Changes => c }.last
        assertTrue(
          summary.fileCount == 1,
          summary.files.head.path == "/tmp/Main.scala",
          summary.files.head.wholeFile,
          rt.pendingChanges.head.kind == ChangeKind.Modify,
        )
      },
      test("keep drops a pending file") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        rt.send("edit Main")
        rt.keep("/tmp/Main.scala")
        assertTrue(
          rt.pendingChanges.isEmpty,
          posted.exists {
            case c: HostMsg.Changes => c.fileCount == 0
            case _                  => false
          },
        )
      },
      test("keepAll drops every pending file") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        rt.send("edit Main")
        assertTrue(rt.pendingChanges.nonEmpty, rt.pendingSets.nonEmpty)
        rt.keepAll()
        assertTrue(rt.pendingChanges.isEmpty, rt.pendingSets.isEmpty)
      },
      test("changes summary tags files with the turn") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("edit Main")
        val tagged = posted.toList.collect { case c: HostMsg.Changes => c }.last
        assertTrue(
          tagged.files.head.turnId.nonEmpty,
          tagged.files.head.turnTitle.nonEmpty,
        )
      },
      test("openDiff posts a sidebar preview of the pending file") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        rt.send("edit Main")
        posted.clear()
        rt.openDiff("call_1")
        assertTrue(posted.exists {
          case HostMsg.DiffPreview(path, _, _, _) => path == "/tmp/Main.scala"
          case _                                  => false
        })
      },
      test("setMode commits plan before later work") {
        val rt = ChatRuntime(_ => ())
        rt.ready()
        rt.setMode("plan")
        assertTrue(rt.state.modeId.contains("plan"), rt.state.planActive)
      },
      test("permissionChoice answers the inbound request") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val lines  = scala.collection.mutable.ListBuffer.empty[String]
        val inner  = AcpTransport.fake()
        val wrap   = new AcpTransport:
          def onData(next: String => Unit): Unit = inner.onData(next)
          def write(data: String): Unit          =
            lines += data
            inner.write(data)
          def close(): Unit = inner.close()
        val rt = ChatRuntime(posted += _, wrap)
        rt.ready()
        posted.clear()
        rt.send("hello")
        assertTrue(posted.exists {
          case p: HostMsg.Permission => p.requestId == "perm-1"
          case _                     => false
        })
        lines.clear()
        rt.permissionChoice("perm-1", "allow-once")
        assertTrue(lines.exists(l => l.contains("\"selected\"") && l.contains("allow-once")))
      },
      test("addChip is included in the next prompt") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val lines  = scala.collection.mutable.ListBuffer.empty[String]
        val inner  = AcpTransport.fake()
        val wrap   = new AcpTransport:
          def onData(next: String => Unit): Unit = inner.onData(next)
          def write(data: String): Unit          =
            lines += data
            inner.write(data)
          def close(): Unit = inner.close()
        val rt = ChatRuntime(posted += _, wrap)
        rt.ready()
        posted.clear()
        lines.clear()
        rt.addChip(PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50)))
        rt.send("explain")
        assertTrue(
          posted.exists {
            case HostMsg.UserMessage(_, "explain", chips, _) =>
              chips.exists(c => PromptChip.formatAtRef(c) == "@src/Foo.scala:10-50")
            case _ => false
          },
          lines.exists(l => l.contains("@src/Foo.scala:10-50") && l.contains("explain")),
        )
      },
      test("mentionQuery uses the search port") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val files  = List(MentionFile("src/Main.scala", "/repo/src/Main.scala"))
        val rt     = ChatRuntime(posted += _, searchFiles = q => if q == "Main" then files else Nil)
        rt.mentionQuery("Main")
        assertTrue(posted.toList == List(HostMsg.MentionResults("Main", files)))
      },
      test("empty send still runs when a chip is pending") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.addChip(PromptChip.fromFile("/repo/src/Foo.scala", Some("/repo")))
        rt.send("   ")
        assertTrue(posted.exists {
          case HostMsg.UserMessage(_, "", chips, _) => chips.exists(_.path == "src/Foo.scala")
          case _                                    => false
        })
      },
      test("removeChip drops a pending chip so empty send is a no-op") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val chip   = PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50))
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        rt.addChip(chip)
        posted.clear()
        rt.removeChip(chip.absPath, chip.startLine, chip.endLine)
        rt.send("")
        assertTrue(posted.isEmpty)
      },
      test("ready posts advertised models on sessionMeta") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        assertTrue(posted.exists {
          case m: HostMsg.SessionMeta =>
            m.modelId == "grok-4.6" &&
            m.availableModels.exists(_.modelId == "grok-4.6") &&
            m.availableModels.exists(_.modelId == "grok-code-fast-1")
          case _ => false
        })
      },
      test("setModel writes session/set_model") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val inner = AcpTransport.fake()
        val wrap  = new AcpTransport:
          def onData(next: String => Unit): Unit = inner.onData(next)
          def write(data: String): Unit          =
            lines += data
            inner.write(data)
          def close(): Unit = inner.close()
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, wrap)
        rt.ready()
        posted.clear()
        lines.clear()
        rt.setModel("grok-code-fast-1")
        assertTrue(
          lines.exists(l => l.contains("session/set_model") && l.contains("grok-code-fast-1")),
          posted.exists {
            case m: HostMsg.SessionMeta => m.modelId == "grok-code-fast-1"
            case _                      => false
          },
        )
      },
      test("cycleMode walks Normal to Plan") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.cycleMode()
        assertTrue(
          rt.state.modeId.contains("plan"),
          rt.state.planActive,
          posted.exists {
            case m: HostMsg.SessionMeta => m.modeId == "plan"
            case _                      => false
          },
        )
      },
      test("cancel notifies session/cancel and does not answer a parked permission") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val lines  = scala.collection.mutable.ListBuffer.empty[String]
        val inner  = AcpTransport.fake()
        val wrap   = new AcpTransport:
          def onData(next: String => Unit): Unit = inner.onData(next)
          def write(data: String): Unit          =
            lines += data
            inner.write(data)
          def close(): Unit = inner.close()
        val rt = ChatRuntime(posted += _, wrap)
        rt.ready()
        rt.send("hello")
        lines.clear()
        HostDispatch(rt, WebviewMsg.PermissionPark("perm-1"), _ => ())
        assertTrue(!lines.exists(_.contains("selected")))
        rt.cancel()
        assertTrue(lines.exists(_.contains("session/cancel")))
      },
      test("openPicker ranks a used current session first") {
        for
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
          rows   = List(
            SessionRow("old", "Earlier work", activityMs = 9),
            SessionRow("sess_test", "Current", activityMs = 1),
          )
          rt = ChatRuntime(posted += _, listSessions = () => rows)
          listed <- ZIO.succeed {
            rt.ready()
            rt.send("hello")
            posted.clear()
            rt.openPicker()
            posted.toList.collectFirst { case HostMsg.SessionList(sessions, _, true) => sessions }.getOrElse(Nil)
          }
        yield assertTrue(
          listed.map(_.id) == List("sess_test", "old"),
          listed.headOption.exists(_.activityMs >= now),
        )
      } @@ TestAspect.withLiveClock,
      test("ready posts a sessionList after session/new") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rows   = List(SessionRow("disk-1", "Earlier work", activityMs = 9))
        val rt     = ChatRuntime(posted += _, listSessions = () => rows)
        rt.ready()
        assertTrue(
          posted.exists {
            case HostMsg.SessionList(sessions, _, false) => sessions == rows
            case _                                       => false
          },
          posted.exists {
            case HostMsg.AvailableCommands(cmds) =>
              cmds.exists(_.name == "new") && cmds.exists(_.name == "resume") && cmds.exists(_.name == "model")
            case _ => false
          },
        )
      },
      test("switching resume drops the previous session/load replay") {
        var listener: String => Unit = _ => ()
        var heldDisk                 = ""
        var heldLive                 = ""
        val agent                    = FakeAgent()
        val transport                = new AcpTransport:
          def onData(next: String => Unit): Unit = listener = next
          def write(data: String): Unit          =
            val (lines, _) = Ndjson.split("", data)
            lines.foreach { line =>
              Rpc.parse(line).foreach {
                case Rpc.Request(id, "session/load", params) =>
                  val sid    = params.as[SessionLoadParams].toOption.map(_.sessionId).getOrElse("")
                  val ndjson =
                    if sid == "sess_disk" then ChatRuntimeSpec.loadReplay(id, sid, "hello from disk", "welcome back")
                    else ChatRuntimeSpec.loadReplay(id, sid, "hello from live", "live agent")
                  if sid == "sess_disk" then heldDisk = ndjson else heldLive = ndjson
                case msg => listener(agent.encodeReplies(msg))
              }
            }
          end write
          def close(): Unit = ()
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, transport)
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_disk")
        rt.resumeSession("sess_live")
        listener(heldDisk)
        val afterStale = ChatRuntimeSpec.snapshotUsers(posted.toList)
        listener(heldLive)
        val users  = ChatRuntimeSpec.snapshotUsers(posted.toList)
        val lastId = posted.toList.reverse.collectFirst { case m: HostMsg.SessionMeta => m.sessionId }
        assertTrue(
          afterStale.isEmpty,
          users == List("hello from live"),
          lastId.contains("sess_live"),
        )
      },
      test("newSession drops a cancelled session/load replay") {
        var listener: String => Unit = _ => ()
        var held                     = ""
        val agent                    = FakeAgent()
        val transport                = new AcpTransport:
          def onData(next: String => Unit): Unit = listener = next
          def write(data: String): Unit          =
            val (lines, _) = Ndjson.split("", data)
            lines.foreach { line =>
              Rpc.parse(line).foreach {
                case msg @ Rpc.Request(_, "session/load", _) => held = agent.encodeReplies(msg)
                case msg                                     => listener(agent.encodeReplies(msg))
              }
            }
          def close(): Unit = ()
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, transport)
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_disk")
        rt.newSession()
        listener(held)
        val users = ChatRuntimeSpec.snapshotUsers(posted.toList)
        assertTrue(
          posted.exists {
            case HostMsg.ClearTranscript => true
            case _                       => false
          },
          !users.contains("hello from disk"),
        )
      },
      test("late session/new does not steal a resumed session") {
        var listener: String => Unit = _ => ()
        var heldNew                  = ""
        val agent                    = FakeAgent()
        val transport                = new AcpTransport:
          def onData(next: String => Unit): Unit = listener = next
          def write(data: String): Unit          =
            val (lines, _) = Ndjson.split("", data)
            lines.foreach { line =>
              Rpc.parse(line).foreach {
                case msg @ Rpc.Request(_, "session/new", _) => heldNew = agent.encodeReplies(msg)
                case msg                                    => listener(agent.encodeReplies(msg))
              }
            }
          def close(): Unit = ()
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, transport)
        rt.ready()
        rt.resumeSession("sess_disk")
        posted.clear()
        listener(heldNew)
        val ids = posted.toList.collect { case m: HostMsg.SessionMeta => m.sessionId }
        assertTrue(!ids.contains("sess_test"))
      },
      test("resumeSession posts the disk title on sessionMeta") {
        val rows   = List(SessionRow("sess_disk", "Walked history"))
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, listSessions = () => rows)
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_disk")
        assertTrue(
          posted.exists {
            case m: HostMsg.SessionMeta => m.sessionId == "sess_disk" && m.title == "Walked history"
            case _                      => false
          }
        )
      },
      test("resumeSession replays disk history into the transcript") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_disk")
        val model = posted.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        val snap  = posted.toList.collect { case HostMsg.Transcript(turns) => turns }.flatten
        assertTrue(
          posted.exists {
            case HostMsg.Transcript(_) => true
            case _                     => false
          },
          !posted.exists {
            case _: HostMsg.UserMessage => true
            case _: HostMsg.AgentChunk  => true
            case _                      => false
          },
          snap.exists(t => t.user.exists(_.text == "hello from disk") && t.agent.contains("welcome back")),
          model.turns.exists(t => t.user.exists(_.text == "hello from disk") && t.agent.contains("welcome back")),
        )
      },
      test("locked session/load posts SessionLocked and leaves the current session") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _, AcpTransport.fake(FakeAgent(lockLoad = true)))
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_live")
        assertTrue(
          posted.exists {
            case HostMsg.SessionLocked("sess_live", msg) => msg.contains("TUI")
            case _                                       => false
          },
          !posted.exists {
            case _: HostMsg.UserMessage => true
            case _                      => false
          },
        )
      },
      test("newSession deletes an unused session this process created") {
        val deleted = scala.collection.mutable.ListBuffer.empty[String]
        val posted  = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt      = ChatRuntime(posted += _, scheduleEmptyDelete = deleted += _)
        rt.ready()
        rt.newSession()
        assertTrue(deleted.contains("sess_test"))
      },
      test("newSession keeps a session that already has a prompt") {
        val deleted = scala.collection.mutable.ListBuffer.empty[String]
        val posted  = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt      = ChatRuntime(posted += _, scheduleEmptyDelete = deleted += _)
        rt.ready()
        rt.send("hello")
        deleted.clear()
        rt.newSession()
        assertTrue(deleted.isEmpty)
      },
      test("/model in the composer switches by id or display name") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("/model Grok Code Fast")
        assertTrue(
          posted.exists {
            case m: HostMsg.SessionMeta => m.modelId == "grok-code-fast-1"
            case _                      => false
          },
          !posted.exists {
            case HostMsg.UserMessage(_, "/model Grok Code Fast", _, _) => true
            case _                                                     => false
          },
        )
      },
      test("unknown /model posts an error and does not prompt") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("/model nope")
        assertTrue(
          posted.exists {
            case HostMsg.Error(message, _) => message.contains("nope")
            case _                         => false
          },
          !posted.exists {
            case _: HostMsg.UserMessage => true
            case _                      => false
          },
        )
      },
      test("/new in the composer starts a new session") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.send("/new")
        assertTrue(
          posted.exists {
            case HostMsg.ClearTranscript => true
            case _                       => false
          },
          !posted.exists {
            case HostMsg.UserMessage(_, "/new", _, _) => true
            case _                                    => false
          },
        )
      },
      test("setSetting posts the patched settings") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.setSetting("useCtrlEnterToSend", true)
        assertTrue(posted.exists {
          case s: HostMsg.Settings => s.useCtrlEnterToSend
          case _                   => false
        })
      },
      test("renameSession posts the new title on sessionMeta and the list") {
        var rows   = List(SessionRow("sess_test", "Old"))
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(
          posted += _,
          listSessions = () => rows,
          renameOnDisk = (id, op) =>
            op match
              case RenameOp.Manual(t) =>
                rows = rows.map(r => if r.id == id then r.copy(title = t) else r)
                rows.find(_.id == id)
              case RenameOp.Auto => rows.find(_.id == id),
        )
        rt.ready()
        posted.clear()
        rt.renameSession("sess_test", RenameOp.Manual("Plan"))
        assertTrue(
          posted.exists {
            case m: HostMsg.SessionMeta => m.title == "Plan"
            case _                      => false
          },
          posted.exists {
            case HostMsg.SessionList(sessions, _, _) =>
              sessions.exists(r => r.id == "sess_test" && r.title == "Plan")
            case _ => false
          },
        )
      },
      test("send /rename applies a manual title and is not a prompt") {
        var rows   = List(SessionRow("sess_test", "Old"))
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(
          posted += _,
          listSessions = () => rows,
          renameOnDisk = (id, op) =>
            op match
              case RenameOp.Manual(t) =>
                rows = rows.map(r => if r.id == id then r.copy(title = t) else r)
                rows.find(_.id == id)
              case RenameOp.Auto => rows.find(_.id == id),
        )
        rt.ready()
        posted.clear()
        rt.send("/rename Plan")
        assertTrue(
          posted.exists {
            case m: HostMsg.SessionMeta => m.title == "Plan"
            case _                      => false
          },
          !posted.exists {
            case HostMsg.UserMessage(_, "/rename Plan", _, _) => true
            case _                                            => false
          },
        )
      },
      test("send /delete does not wipe without confirm") {
        var deleted = false
        val posted  = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt      = ChatRuntime(
          posted += _,
          deleteOnDisk = _ =>
            deleted = true; true,
        )
        rt.ready()
        posted.clear()
        rt.send("/delete")
        assertTrue(
          !deleted,
          !posted.exists {
            case HostMsg.ClearTranscript => true
            case _                       => false
          },
        )
      },
      test("deleteSession of another id refreshes the open picker") {
        var rows   = List(SessionRow("keep", "Keep", activityMs = 2), SessionRow("gone", "Gone", activityMs = 1))
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(
          posted += _,
          listSessions = () => rows,
          deleteOnDisk = id =>
            rows = rows.filterNot(_.id == id)
            true,
        )
        rt.ready()
        rt.openPicker()
        posted.clear()
        rt.deleteSession("gone")
        assertTrue(
          posted.exists {
            case HostMsg.SessionList(sessions, _, true) => sessions.map(_.id) == List("keep")
            case _                                      => false
          }
        )
      },
    )

  def loadReplay(id: RpcId, sessionId: String, user: String, agent: String): String =
    Ndjson.encodeChunk(
      List(
        Rpc.toLine(
          Rpc.notifyOf("session/update", AcpSessionNotify(sessionId, AcpUpdate.User(AcpContent.Text(user))))
        ),
        Rpc.toLine(
          Rpc.notifyOf("session/update", AcpSessionNotify(sessionId, AcpUpdate.Agent(AcpContent.Text(agent))))
        ),
        Rpc.toLine(Rpc.ok(id, SessionLoadResult(sessionId).asJson)),
      )
    )

  def snapshotUsers(posted: List[HostMsg]): List[String] =
    posted.flatMap {
      case HostMsg.UserMessage(_, text, _, _) => List(text)
      case HostMsg.Transcript(turns)          => turns.flatMap(_.user.map(_.text))
      case _                                  => Nil
    }
end ChatRuntimeSpec
