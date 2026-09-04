package groksbeard.core

import zio.test.*

object ChatRuntimeSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatRuntime")(
      test("ready posts sessionMeta and available commands") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        val tags = posted.toList.map {
          case HostMsg.Ready                      => "ready"
          case HostMsg.SessionMeta(_, _, _, _, _) => "sessionMeta"
          case HostMsg.AvailableCommands(_)       => "availableCommands"
          case _: HostMsg.Settings                => "settings"
          case other                              => other.toString
        }
        assertTrue(
          tags.contains("ready"),
          tags.contains("sessionMeta"),
          tags.contains("availableCommands"),
          posted.exists {
            case HostMsg.SessionMeta(_, _, "normal", modes, _) => modes.exists(_.id == "plan")
            case _                                             => false
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
            case HostMsg.Queued(1) => true
            case _                 => false
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
      test("queue and cancel post queued counts") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.queue("later")
        rt.cancel()
        assertTrue(
          posted.toList == List(HostMsg.Queued(1), HostMsg.Queued(0))
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
            case HostMsg.SessionMeta(_, _, "plan", _, _) => true
            case _                                       => false
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
            case HostMsg.AvailableCommands(cmds) => cmds.exists(_.name == "new") && cmds.exists(_.name == "resume")
            case _                               => false
          },
        )
      },
      test("resumeSession replays disk history into the transcript") {
        val posted = scala.collection.mutable.ListBuffer.empty[HostMsg]
        val rt     = ChatRuntime(posted += _)
        rt.ready()
        posted.clear()
        rt.resumeSession("sess_disk")
        val model = posted.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
        assertTrue(
          posted.exists {
            case HostMsg.UserMessage(_, "hello from disk", _, _) => true
            case _                                               => false
          },
          posted.exists {
            case HostMsg.AgentChunk(_, "welcome back", _) => true
            case _                                        => false
          },
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
    )
end ChatRuntimeSpec
