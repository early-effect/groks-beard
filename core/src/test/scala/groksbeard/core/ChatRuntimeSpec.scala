package groksbeard.core

import java.util.concurrent.TimeUnit
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ChatRuntimeSpec extends ZIOSpecDefault:
  def spec =
    suite("ChatRuntime")(
      test("ready posts sessionMeta and available commands") {
        chat() { (rt, posted) =>
          rt.ready *> posted.get.map { msgs =>
            val tags = msgs.map {
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
              msgs.exists {
                case m: HostMsg.SessionMeta => m.modeId == "normal" && m.availableModes.exists(_.id == "plan")
                case _                      => false
              },
            )
          }
        }
      },
      test("ready posts sessionMeta while beforeInitialize is still waiting") {
        Promise.make[Nothing, Unit].flatMap { hold =>
          chat(beforeInitialize = hold.await) { (rt, posted) =>
            for
              _    <- rt.ready
              msgs <- posted.get
              _    <- hold.succeed(())
            yield assertTrue(
              msgs.exists {
                case HostMsg.Ready => true
                case _             => false
              },
              msgs.exists {
                case _: HostMsg.SessionMeta => true
                case _                      => false
              },
            )
          }
        }
      } @@ TestAspect.timeout(5.seconds),
      test("send during MCP wait does not wait for local MCP") {
        Promise.make[Nothing, Unit].flatMap { hold =>
          chat(beforeInitialize = hold.await) { (rt, posted) =>
            for
              _    <- rt.ready
              _    <- posted.set(Nil)
              _    <- rt.send("hello")
              msgs <- posted.get
              _    <- hold.succeed(())
            yield assertTrue(msgs.exists {
              case HostMsg.UserMessage(_, "hello", _, _) => true
              case _                                     => false
            })
          }
        }
      } @@ TestAspect.timeout(5.seconds),
      test("send before ready queues until the session exists") {
        chat() { (rt, posted) =>
          for
            _     <- rt.send("hello")
            early <- posted.get
            _     <- rt.ready
            later <- posted.get
          yield assertTrue(
            early.exists {
              case HostMsg.Queued(items) => items.exists(_.text == "hello")
              case _                     => false
            },
            later.exists {
              case HostMsg.UserMessage(_, "hello", _, _) => true
              case _                                     => false
            },
          )
        }
      },
      test("settings can update while beforeInitialize is still waiting") {
        Promise.make[Nothing, Unit].flatMap { hold =>
          chat(beforeInitialize = hold.await) { (rt, posted) =>
            val next = SettingsState.defaults.copy(cliPath = "/tmp/grok")
            for
              _    <- rt.ready
              _    <- posted.set(Nil)
              _    <- rt.replaceSettings(next)
              msgs <- posted.get
              _    <- hold.succeed(())
            yield assertTrue(msgs.exists {
              case m: HostMsg.Settings => m.cliPath == "/tmp/grok"
              case _                   => false
            })
            end for
          }
        }
      } @@ TestAspect.timeout(5.seconds),
      test("fork copies the session and loads the child") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/fork --no-worktree")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.sessionId == "sess_fork"
              case _                      => false
            },
            msgs.exists {
              case HostMsg.Transcript(_) => true
              case _                     => false
            },
          )
        }
      },
      test("fork MethodNotFound is CLI does not advertise") {
        chat(transport = AcpTransport.fake(FakeAgent(rejectFork = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/fork")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.Error(Fork.MissingCli, _) => true
            case _                                 => false
          })
        }
      },
      test("fork --worktree without capability errors") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/fork --worktree")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.Error(Fork.MissingWorktree, _) => true
            case _                                      => false
          })
        }
      },
      test("fork without flags asks when worktree methods are advertised") {
        chat(transport = AcpTransport.fake(FakeAgent(worktreeMeta = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/fork try the async approach")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.ForkAsk("try the async approach") => true
            case _                                         => false
          })
        }
      },
      test("fork directive is sent after the child loads") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/fork --no-worktree ping the child")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.UserMessage(_, "ping the child", _, _) => true
            case _                                              => false
          })
        }
      },
      test("send posts user, thought, agent, tool, then turnEnd") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("hello")
            msgs <- posted.get
            tags = msgs.collect {
              case HostMsg.UserMessage(_, text, _, _) => s"user:$text"
              case HostMsg.ThoughtChunk(_, text)      => s"thought:$text"
              case HostMsg.AgentChunk(_, text, _)     => s"agent:$text"
              case HostMsg.ToolCall(_, tool)          => s"tool:${tool.title}"
              case HostMsg.ToolChunk(_, _, text, _)   => s"chunk:$text"
              case HostMsg.TurnEnd(_, reason)         => s"end:${StopReason.wire(reason)}"
            }
          yield assertTrue(
            tags.head == "user:hello",
            tags.exists(_.startsWith("thought:")),
            tags.contains("agent:hello"),
            tags.exists(_.startsWith("tool:Edit")),
            tags.last == "end:end_turn",
          )
        }
      },
      test("send folds through ChatModel into one completed turn") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("hello")
            msgs <- posted.get
            model = msgs.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
            turn  = model.turns.head
          yield assertTrue(
            turn.user.exists(_.text == "hello"),
            turn.thought.contains("Considering"),
            turn.agent == "hello",
            turn.tools.exists(_.title.contains("Edit")),
            turn.stopReason.contains(StopReason.EndTurn),
            !ChatModel.turnIsRunning(model),
          )
        }
      },
      test("send while a turn is running queues the follow-up until the turn ends") {
        ZIO.scoped {
          for
            posted <- Ref.make(List.empty[HostMsg])
            slot   <- Ref.make(Option.empty[ChatRuntime])
            rt     <- ChatRuntime
              .make()
              .provideSome[Scope](
                ChatEnv.test(post =
                  msg =>
                    posted.update(_ :+ msg) *> (msg match
                      case HostMsg.UserMessage(_, "hello", _, _) =>
                        slot.get.flatMap {
                          case Some(r) => r.send("later")
                          case None    => ZIO.unit
                        }
                      case _ => ZIO.unit)
                )
              )
            _    <- slot.set(Some(rt))
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("hello")
            msgs <- posted.get
            users = msgs.collect { case HostMsg.UserMessage(_, text, _, _) => text }
          yield assertTrue(
            msgs.exists {
              case HostMsg.Queued(items) => items.map(_.text) == List("later")
              case _                     => false
            },
            users == List("hello", "later"),
          )
        }
      },
      test("rewind points then execute posts Rewound") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.send("first")
            _    <- rt.send("second")
            _    <- posted.set(Nil)
            _    <- rt.openRewind
            _    <- rt.rewindTo(0)
            msgs <- posted.get
            model = msgs.foldLeft(
              ChatModel.empty.copy(
                turns = List(
                  TurnView("t1", user = Some(TurnUser("first"))),
                  TurnView("t2", user = Some(TurnUser("second"))),
                )
              )
            )(ChatModel.applyMsg)
          yield assertTrue(
            msgs.exists {
              case HostMsg.RewindList(points) => points.map(_.promptIndex) == List(0, 1)
              case _                          => false
            },
            msgs.exists {
              case HostMsg.Rewound(0) => true
              case _                  => false
            },
            model.turns.size == 1,
          )
        }
      },
      test("/loop without args posts usage") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/loop")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.contains("/loop")
              case _                         => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("/loop 5m prompt starts a loop and fires immediately") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/loop 5m check ci")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Tasks(rows) =>
                rows.exists(r => r.kind == TaskKind.Loop && r.label == "check ci" && r.owned)
              case _ => false
            },
            msgs.exists {
              case HostMsg.UserMessage(_, "check ci", _, _) => true
              case _                                        => false
            },
          )
        }
      },
      test("x.ai task_backgrounded posts Tasks") {
        chat() { (rt, posted) =>
          val line = Ndjson.encode(
            Rpc.toLine(
              Rpc.notifyOf(
                "_x.ai/session/update",
                Json.Obj(
                  "sessionId" -> Json.Str("sess_test"),
                  "update"    -> Json.Obj(
                    "sessionUpdate" -> Json.Str("task_backgrounded"),
                    "task_id"       -> Json.Str("t1"),
                    "description"   -> Json.Str("Compile"),
                    "command"       -> Json.Str("sbt compile"),
                  ),
                ),
              )
            )
          )
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.ingestData(line)
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.Tasks(rows) => rows.exists(r => r.id.value == "t1" && r.label == "Compile")
            case _                   => false
          })
        }
      },
      test("send /rewind intercepts and lists points") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.send("first")
            _    <- posted.set(Nil)
            _    <- rt.send("/rewind")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.RewindList(points) => points.map(_.promptIndex) == List(0, 1)
              case _                          => false
            }
          )
        }
      },
      test("rewind while a turn is running is refused") {
        chat(transport = AcpTransport.fake(FakeAgent(hangPrompt = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.send("hello")
            _    <- posted.set(Nil)
            _    <- rt.openRewind
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.contains("Stop the turn")
              case _                         => false
            },
            !msgs.exists {
              case HostMsg.RewindList(_) => true
              case _                     => false
            },
          )
        }
      },
      test("listMcps posts the inspect inventory") {
        val metals = McpServerView("metals", "http", "http://localhost:56126/mcp")
        ZIO.scoped {
          for
            posted <- Ref.make(List.empty[HostMsg])
            rt     <- ChatRuntime
              .make()
              .provideSome[Scope](
                ChatEnv.test(
                  post = msg => posted.update(_ :+ msg),
                  mcps = Mcps.test(List(metals)),
                )
              )
            _     <- rt.listMcps
            msgs  <- posted.get
            _     <- rt.setMcpEnabled("metals", enabled = false)
            after <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.McpServers(rows) => rows.exists(_.name == "metals") && rows.head.enabled
              case _                        => false
            },
            after.lastOption.exists {
              case HostMsg.McpServers(rows) => rows.exists(r => r.name == "metals" && !r.enabled)
              case _                        => false
            },
          )
        }
      },
      test("empty send is a no-op") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("   ")
            msgs <- posted.get
          yield assertTrue(msgs.isEmpty)
        }
      },
      test("MCP AuthRequired is a notice and does not cancel the turn") {
        chat(transport = AcpTransport.fake(FakeAgent(hangPrompt = true))) { (rt, posted) =>
          for
            _ <- rt.ready
            _ <- posted.set(Nil)
            _ <- rt.send("hello")
            _ <- rt.queue("later")
            _ <- rt.noteAgentLine(
              """ERROR worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer resource_metadata=\"https://mcp.atlassian.com/.well-known/oauth-protected-resource/v1/mcp/authv2\", error=\"invalid_token\"" })"""
            )
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.toLowerCase.contains("atlassian")
              case _                         => false
            },
            !msgs.exists {
              case HostMsg.TurnEnd(_, _) => true
              case _                     => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "later", _, _) => true
              case _                                     => false
            },
            msgs.exists {
              case HostMsg.Queued(items) => items.map(_.text) == List("later")
              case _                     => false
            },
          )
        }
      },
      test("MCP AuthRequired during session/load is a notice and does not fail the load") {
        delayedLoad().flatMap { case (transport, held) =>
          chat(transport = transport) { (rt, posted) =>
            for
              _ <- rt.ready
              _ <- rt.resumeSession("sess_disk")
              _ <- posted.set(Nil)
              _ <- rt.noteAgentLine(
                """ERROR worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { www_authenticate_header: "Bearer resource_metadata=\"https://mcp.atlassian.com/.well-known/oauth-protected-resource/v1/mcp/authv2\", error=\"invalid_token\"" })"""
              )
              mid  <- posted.get
              _    <- rt.ingestData(held.disk)
              msgs <- posted.get
            yield assertTrue(
              mid.exists {
                case HostMsg.Error(message, _) => message.toLowerCase.contains("atlassian")
                case _                         => false
              },
              !mid.exists {
                case _: HostMsg.SessionLocked => true
                case _                        => false
              },
              msgs.exists {
                case HostMsg.Transcript(_) => true
                case _                     => false
              },
            )
          }
        }
      },
      test("agent process exit unsticks a hung prompt") {
        chat(transport = AcpTransport.fake(FakeAgent(hangPrompt = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("hello")
            _    <- rt.queue("later")
            _    <- rt.noteAgentGone
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.toLowerCase.contains("stopped")
              case _                         => false
            },
            msgs.exists {
              case HostMsg.TurnEnd(_, StopReason.Cancelled) => true
              case _                                        => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "later", _, _) => true
              case _                                     => false
            },
            msgs.exists {
              case HostMsg.Queued(items) => items.map(_.text) == List("later")
              case _                     => false
            },
          )
        }
      },
      test("agent process exit unsticks a pending session/load") {
        delayedLoad().flatMap { case (transport, held) =>
          chat(transport = transport) { (rt, posted) =>
            for
              _     <- rt.ready
              _     <- rt.resumeSession("sess_disk")
              _     <- posted.set(Nil)
              _     <- rt.noteAgentGone
              mid   <- posted.get
              _     <- rt.ingestData(held.disk)
              after <- posted.get
            yield assertTrue(
              mid.exists {
                case HostMsg.SessionLocked("sess_disk", msg) => msg.toLowerCase.contains("resume")
                case _                                       => false
              },
              mid.exists {
                case HostMsg.Error(message, _) => message.toLowerCase.contains("stopped")
                case _                         => false
              },
              !after.exists {
                case HostMsg.Transcript(_)  => true
                case _: HostMsg.UserMessage => true
                case _: HostMsg.AgentChunk  => true
                case _                      => false
              },
            )
          }
        }
      },
      test("queue parks the follow-up text without sending it") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.queue("later")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Queued(items) => items.map(_.text) == List("later")
              case _                     => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("cancel sends the next parked follow-up") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.queue("later")
            _    <- rt.cancel
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.UserMessage(_, "later", _, _) => true
            case _                                     => false
          })
        }
      },
      test("sendNow of a later row cancels and runs that prompt next") {
        chat(transport = AcpTransport.fake(FakeAgent(hangPrompt = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("hello")
            _    <- rt.queue("first")
            _    <- rt.queue("second")
            _    <- posted.set(Nil)
            _    <- rt.sendNow("q2")
            msgs <- posted.get
            left = msgs.collect { case HostMsg.Queued(items) => items.map(_.text) }.lastOption.getOrElse(Nil)
          yield assertTrue(
            msgs.exists {
              case HostMsg.TurnEnd(_, StopReason.Cancelled) => true
              case _                                        => false
            },
            msgs.exists {
              case HostMsg.UserMessage(_, "second", _, _) => true
              case _                                      => false
            },
            left == List("first"),
          )
        }
      },
      test("dropQueued removes a parked follow-up without sending it") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.queue("keep")
            _    <- rt.queue("drop-me")
            _    <- posted.set(Nil)
            _    <- rt.dropQueued("q2")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Queued(items) => items.map(_.text) == List("keep")
              case _                     => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "drop-me", _, _) => true
              case _                                       => false
            },
          )
        }
      },
      test("send ingests the fake edit into Changes") {
        val disk = Map("/tmp/Main.scala" -> "aaa\nobject Main\nccc\n")
        chat(readDisk = disk.get) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("edit Main")
            msgs <- posted.get
            summary = msgs.collect { case c: HostMsg.Changes => c }.last
          yield assertTrue(
            summary.fileCount == 1,
            summary.files.head.path == "/tmp/Main.scala",
            summary.files.head.wholeFile,
            rt.pendingChanges.head.kind == ChangeKind.Modify,
          )
        }
      },
      test("keep drops a pending file") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.send("edit Main")
            _    <- rt.keep("/tmp/Main.scala")
            msgs <- posted.get
          yield assertTrue(
            rt.pendingChanges.isEmpty,
            msgs.exists {
              case c: HostMsg.Changes => c.fileCount == 0
              case _                  => false
            },
          )
        }
      },
      test("keepAll drops every pending file") {
        chat() { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.send("edit Main")
            _ <- ZIO.succeed(assertTrue(rt.pendingChanges.nonEmpty, rt.pendingSets.nonEmpty))
            _ <- rt.keepAll
          yield assertTrue(rt.pendingChanges.isEmpty, rt.pendingSets.isEmpty)
        }
      },
      test("ingest persists the pending sets") {
        for
          saved  <- Ref.make(List.empty[List[ChangeSet]])
          result <- chat(persistChanges = sets => saved.update(_ :+ sets)) { (rt, _) =>
            for
              _     <- rt.ready
              _     <- saved.set(Nil)
              _     <- rt.send("edit Main")
              snaps <- saved.get
            yield assertTrue(
              snaps.nonEmpty,
              snaps.last.exists(_.files.exists(_.path == "/tmp/Main.scala")),
            )
          }
        yield result
      },
      test("keepAll persists an empty snapshot") {
        for
          saved  <- Ref.make(List.empty[List[ChangeSet]])
          result <- chat(persistChanges = sets => saved.update(_ :+ sets)) { (rt, _) =>
            for
              _     <- rt.ready
              _     <- rt.send("edit Main")
              _     <- saved.set(Nil)
              _     <- rt.keepAll
              snaps <- saved.get
            yield assertTrue(snaps.last.isEmpty)
          }
        yield result
      },
      test("restoreChanges before ready posts on ready and does not persist") {
        val file =
          FileChange("/a.ts", ChangeKind.Modify, 1, 1, true, "c", oldSnapshot = Some("o"), newSnapshot = Some("n"))
        val set = ChangeSet("s", "t1", "edit", List(file), 1L)
        for
          saved  <- Ref.make(List.empty[List[ChangeSet]])
          result <- chat(persistChanges = sets => saved.update(_ :+ sets)) { (rt, posted) =>
            for
              _      <- rt.restoreChanges(List(set))
              snaps  <- saved.get
              before <- posted.get
              _      <- rt.ready
              after  <- posted.get
              snaps2 <- saved.get
            yield assertTrue(
              snaps.isEmpty,
              !before.exists {
                case _: HostMsg.Changes => true
                case _                  => false
              },
              snaps2.isEmpty,
              after.exists {
                case c: HostMsg.Changes => c.fileCount == 1
                case _                  => false
              },
            )
          }
        yield result
        end for
      },
      test("restoreChanges after ready posts immediately") {
        val file =
          FileChange("/a.ts", ChangeKind.Modify, 1, 1, true, "c", oldSnapshot = Some("o"), newSnapshot = Some("n"))
        val set = ChangeSet("s", "t1", "edit", List(file), 1L)
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.restoreChanges(List(set))
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case c: HostMsg.Changes => c.fileCount == 1 && c.files.head.path == "/a.ts"
            case _                  => false
          })
        }
      },
      test("changes summary tags files with the turn") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("edit Main")
            msgs <- posted.get
            tagged = msgs.collect { case c: HostMsg.Changes => c }.last
          yield assertTrue(
            tagged.files.head.turnId.nonEmpty,
            tagged.files.head.turnTitle.nonEmpty,
          )
        }
      },
      test("tool_call locations do not reveal the editor") {
        var followed = List.empty[(String, Option[Int])]
        chat(followFile = (p, l) => followed = followed :+ (p -> l)) { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.send("hello")
            _ <- rt.ingestData(
              Ndjson.encode(
                Rpc.toLine(
                  Rpc.notifyOf(
                    "session/update",
                    AcpSessionNotify(
                      "sess_test",
                      AcpUpdate.ToolCallUpdate(
                        toolCallId = "call_1",
                        locations = List(ToolLocation("/tmp/Main.scala", Some(1))),
                      ),
                    ),
                  )
                )
              )
            )
          yield assertTrue(followed.isEmpty)
        }
      },
      test("tool_call locations stamp the tool path") {
        chat() { (rt, posted) =>
          for
            _ <- rt.ready
            _ <- rt.send("hello")
            _ <- posted.set(Nil)
            _ <- rt.ingestData(
              Ndjson.encode(
                Rpc.toLine(
                  Rpc.notifyOf(
                    "session/update",
                    AcpSessionNotify(
                      "sess_test",
                      AcpUpdate.ToolCallUpdate(
                        toolCallId = "call_1",
                        locations = List(ToolLocation("/tmp/Main.scala", Some(4))),
                      ),
                    ),
                  )
                )
              )
            )
            msgs <- posted.get
            paths = msgs.collect { case HostMsg.ToolCall(_, row) => (row.path, row.line) }
          yield assertTrue(paths.contains((Some("/tmp/Main.scala"), Some(4))))
        }
      },
      test("openFile reveals the path") {
        var followed = List.empty[(String, Option[Int])]
        chat(followFile = (p, l) => followed = followed :+ (p -> l)) { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.openFile("/tmp/Main.scala", Some(4))
            _ <- rt.openFile("  ", None)
          yield assertTrue(followed == List("/tmp/Main.scala" -> Some(4)))
        }
      },
      test("openDiff posts a sidebar preview of the pending file") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.send("edit Main")
            _    <- posted.set(Nil)
            _    <- rt.openDiff("call_1")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.DiffPreview(path, _, _, _) => path == "/tmp/Main.scala"
            case _                                  => false
          })
        }
      },
      test("setMode commits plan before later work") {
        chat() { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.setMode("plan")
          yield assertTrue(rt.state.modeId.contains("plan"), rt.state.planActive)
        }
      },
      test("permissionChoice answers the inbound request") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _       <- rt.ready
            _       <- posted.set(Nil)
            _       <- rt.send("hello")
            hasPerm <- posted.get.map(_.exists {
              case p: HostMsg.Permission => p.requestId == "perm-1"
              case _                     => false
            })
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.permissionChoice("perm-1", "allow-once")
          yield assertTrue(
            hasPerm,
            lines.exists(l => l.contains("\"selected\"") && l.contains("allow-once")),
          )
        }
      },
      test("addChip is included in the next prompt") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- ZIO.succeed(lines.clear())
            _    <- rt.addChip(PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50)))
            _    <- rt.send("explain")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.UserMessage(_, "explain", chips, _) =>
                chips.exists(c => PromptChip.formatAtRef(c) == "@src/Foo.scala:10-50")
              case _ => false
            },
            lines.exists(l => l.contains("@src/Foo.scala:10-50") && l.contains("explain")),
          )
        }
      },
      test("mentionQuery uses the search port") {
        val files = List(MentionFile("src/Main.scala", "/repo/src/Main.scala"))
        chat(searchFiles = q => if q == "Main" then files else Nil) { (rt, posted) =>
          rt.mentionQuery("Main") *> posted.get.map { msgs =>
            assertTrue(msgs == List(HostMsg.MentionResults("Main", files)))
          }
        }
      },
      test("empty send still runs when a chip is pending") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.addChip(PromptChip.fromFile("/repo/src/Foo.scala", Some("/repo")))
            _    <- rt.send("   ")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.UserMessage(_, "", chips, _) => chips.exists(_.path == "src/Foo.scala")
            case _                                    => false
          })
        }
      },
      test("removeChip drops a pending chip so empty send is a no-op") {
        val chip = PromptChip.fromSelection("/repo/src/Foo.scala", Some("/repo"), Some(10), Some(50))
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.addChip(chip)
            _    <- posted.set(Nil)
            _    <- rt.removeChip(chip.absPath, chip.startLine, chip.endLine)
            _    <- rt.send("")
            msgs <- posted.get
          yield assertTrue(msgs.isEmpty)
        }
      },
      test("ready posts advertised models on sessionMeta") {
        chat() { (rt, posted) =>
          rt.ready *> posted.get.map { msgs =>
            assertTrue(msgs.exists {
              case m: HostMsg.SessionMeta =>
                m.modelId == "grok-4.6" &&
                m.availableModels.exists(_.modelId == "grok-4.6") &&
                m.availableModels.exists(_.modelId == "grok-code-fast-1")
              case _ => false
            })
          }
        }
      },
      test("setModel writes session/set_model") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- ZIO.succeed(lines.clear())
            _    <- rt.setModel("grok-code-fast-1")
            msgs <- posted.get
          yield assertTrue(
            lines.exists(l => l.contains("session/set_model") && l.contains("grok-code-fast-1")),
            msgs.exists {
              case m: HostMsg.SessionMeta => m.modelId == "grok-code-fast-1"
              case _                      => false
            },
          )
        }
      },
      test("same-chunk set_mode then mutating terminal/create is rejected") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(FakeAgent(pairSetModeWithTerminal = true)), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _ <- rt.ready
            _ <- posted.set(Nil)
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.setMode("plan")
            blob = lines.mkString
          yield assertTrue(
            rt.state.planActive,
            rt.state.modeId.contains("plan"),
            blob.contains(PlanTerminals.Reject),
            !blob.contains("\"terminalId\":\"term-1\""),
          )
        }
      },
      test("same-chunk set_mode then read-only terminal/create replies with term-1") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(
          AcpTransport.fake(
            FakeAgent(
              pairSetModeWithTerminal = true,
              pairTerminal = TerminalCreateParams(command = "ls"),
            )
          ),
          lines += _,
        )
        chat(transport = wrap) { (rt, posted) =>
          for
            _ <- rt.ready
            _ <- posted.set(Nil)
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.setMode("plan")
            blob = lines.mkString
          yield assertTrue(
            rt.state.planActive,
            blob.contains("\"terminalId\":\"term-1\""),
            !blob.contains(PlanTerminals.Reject),
          )
        }
      },
      test("mutating terminal/create is allowed when plan is off") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, _) =>
          val req = Rpc.request(
            RpcId.Str("rm"),
            "terminal/create",
            TerminalCreateParams(command = "rm", args = List("-rf", "/tmp/beard-probe")).asJson,
          )
          for
            _ <- rt.ready
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.ingestData(Ndjson.encode(Rpc.toLine(req)))
            blob = lines.mkString
          yield assertTrue(
            !rt.state.planActive,
            blob.contains("\"terminalId\":\"term-1\""),
            !blob.contains(PlanTerminals.Reject),
          )
          end for
        }
      },
      test("PTY stdout is posted as ToolChunk events") {
        ZIO.scoped {
          for
            q      <- Queue.unbounded[String]
            posted <- Ref.make(List.empty[HostMsg])
            rt     <- ChatRuntime
              .make()
              .provideSome[Scope](
                ChatEnv.test(
                  post = msg => posted.update(_ :+ msg),
                  terminals = Terminals.streaming(q),
                )
              )
            _ <- rt.ready
            _ <- posted.set(Nil)
            _ <- rt.ingestData(
              Ndjson.encode(
                Rpc.toLine(
                  Rpc.request(
                    RpcId.Str("t"),
                    "terminal/create",
                    TerminalCreateParams(command = "echo", args = List("hi")).asJson,
                  )
                )
              )
            )
            _    <- q.offer("line-1\n")
            _    <- q.offer("line-2\n")
            msgs <-
              def wait: UIO[List[HostMsg]] =
                posted.get.flatMap { m =>
                  val bits = m.collect { case HostMsg.ToolChunk(_, _, t, _) => t }
                  if bits.exists(_.contains("line-1")) && bits.exists(_.contains("line-2")) then ZIO.succeed(m)
                  else ZIO.sleep(10.millis) *> wait
                }
              wait.timeout(2.seconds).someOrFail(new RuntimeException("no ToolChunk"))
          yield
            val bits = msgs.collect { case HostMsg.ToolChunk(_, _, t, _) => t }
            assertTrue(
              msgs.exists {
                case HostMsg.ToolCall(_, row) =>
                  row.kind == ToolKind.Execute && row.input.exists(_.contains("echo"))
                case _ => false
              },
              bits.exists(_.contains("line-1")),
              bits.exists(_.contains("line-2")),
            )
        }
      } @@ TestAspect.withLiveClock,
      test("x.ai/terminal/create is handled") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, _) =>
          val req = Rpc.request(
            RpcId.Str("xt"),
            "x.ai/terminal/create",
            TerminalCreateParams(command = "echo", args = List("hi")).asJson,
          )
          for
            _ <- rt.ready
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.ingestData(Ndjson.encode(Rpc.toLine(req)))
            blob = lines.mkString
          yield assertTrue(
            blob.contains("\"terminalId\":\"term-1\""),
            !blob.contains("Method not found"),
          )
        }
      },
      test("cycleMode walks Normal to Plan") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.cycleMode
            msgs <- posted.get
          yield assertTrue(
            rt.state.modeId.contains("plan"),
            rt.state.planActive,
            msgs.exists {
              case m: HostMsg.SessionMeta => m.modeId == "plan"
              case _                      => false
            },
          )
        }
      },
      test("cancel notifies session/cancel and does not answer a parked permission") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.send("hello")
            _ <- ZIO.succeed(lines.clear())
            _ <- HostDispatch(rt, WebviewMsg.PermissionPark("perm-1"), _ => ZIO.unit)
            _ <- ZIO.succeed(assertTrue(!lines.exists(_.contains("selected"))))
            _ <- rt.cancel
          yield assertTrue(lines.exists(_.contains("session/cancel")))
        }
      },
      test("openPicker ranks a used current session first") {
        for
          now <- Clock.currentTime(TimeUnit.MILLISECONDS)
          rows = List(
            SessionRow("old", "Earlier work", activityMs = 9),
            SessionRow("sess_test", "Current", activityMs = 1),
          )
          result <- chat(listSessions = () => rows) { (rt, posted) =>
            for
              _    <- rt.ready
              _    <- rt.send("hello")
              _    <- posted.set(Nil)
              _    <- rt.openPicker
              msgs <- posted.get
              listed = msgs.collectFirst { case HostMsg.SessionList(sessions, _, true) => sessions }.getOrElse(Nil)
            yield assertTrue(
              listed.map(_.id) == List("sess_test", "old"),
              listed.headOption.exists(_.activityMs >= now),
            )
          }
        yield result
      } @@ TestAspect.withLiveClock,
      test("resume before ready loads instead of opening a new session") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _    <- rt.resumeSession("sess_disk")
            _    <- rt.ready
            msgs <- posted.get
            blob = lines.mkString
          yield assertTrue(
            blob.contains("session/load"),
            blob.contains("sess_disk"),
            !blob.contains("\"method\":\"session/new\""),
            msgs.exists {
              case HostMsg.Transcript(turns) => turns.nonEmpty
              case _                         => false
            },
          )
        }
      },
      test("a second ready does not initialize again") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, _) =>
          for
            _ <- rt.ready
            _ <- ZIO.succeed(lines.clear())
            _ <- rt.ready
            blob = lines.mkString
          yield assertTrue(!blob.contains("initialize"), !blob.contains("session/new"))
        }
      },
      test("ready posts a sessionList after session/new") {
        val rows = List(SessionRow("disk-1", "Earlier work", activityMs = 9))
        chat(listSessions = () => rows) { (rt, posted) =>
          rt.ready *> posted.get.map { msgs =>
            assertTrue(
              msgs.exists {
                case HostMsg.SessionList(sessions, _, false) => sessions == rows
                case _                                       => false
              },
              msgs.exists {
                case HostMsg.AvailableCommands(cmds) =>
                  cmds.exists(_.name == "new") && cmds.exists(_.name == "resume") && cmds.exists(_.name == "model")
                case _ => false
              },
            )
          }
        }
      },
      test("switching resume drops the previous session/load replay") {
        delayedLoad().flatMap { case (transport, held) =>
          chat(transport = transport) { (rt, posted) =>
            for
              _     <- rt.ready
              _     <- posted.set(Nil)
              _     <- rt.resumeSession("sess_disk")
              _     <- rt.resumeSession("sess_live")
              _     <- rt.ingestData(held.disk)
              after <- posted.get.map(ChatRuntimeSpec.snapshotUsers)
              _     <- rt.ingestData(held.live)
              msgs  <- posted.get
              users  = ChatRuntimeSpec.snapshotUsers(msgs)
              lastId = msgs.reverse.collectFirst { case m: HostMsg.SessionMeta => m.sessionId }
            yield assertTrue(
              after.isEmpty,
              users == List("hello from live"),
              lastId.contains("sess_live"),
            )
          }
        }
      },
      test("newSession drops a cancelled session/load replay") {
        delayedLoad().flatMap { case (transport, held) =>
          chat(transport = transport) { (rt, posted) =>
            for
              _    <- rt.ready
              _    <- posted.set(Nil)
              _    <- rt.resumeSession("sess_disk")
              _    <- rt.newSession
              _    <- rt.ingestData(held.disk)
              msgs <- posted.get
              users = ChatRuntimeSpec.snapshotUsers(msgs)
            yield assertTrue(
              msgs.exists {
                case HostMsg.ClearTranscript => true
                case _                       => false
              },
              !users.contains("hello from disk"),
            )
          }
        }
      },
      test("late session/new does not steal a resumed session") {
        delayedNew().flatMap { case (transport, heldNew) =>
          chat(transport = transport) { (rt, posted) =>
            for
              _    <- rt.ready
              _    <- rt.resumeSession("sess_disk")
              _    <- posted.set(Nil)
              _    <- rt.ingestData(heldNew.get)
              msgs <- posted.get
              ids = msgs.collect { case m: HostMsg.SessionMeta => m.sessionId }
            yield assertTrue(!ids.contains("sess_test"))
          }
        }
      },
      test("resumeSession posts the disk title on sessionMeta") {
        val rows = List(SessionRow("sess_disk", "Walked history"))
        chat(listSessions = () => rows) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.resumeSession("sess_disk")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case m: HostMsg.SessionMeta => m.sessionId == "sess_disk" && m.title == "Walked history"
            case _                      => false
          })
        }
      },
      test("resumeSession replays disk history into the transcript") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.resumeSession("sess_disk")
            msgs <- posted.get
            model = msgs.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
            snap  = msgs.collect { case HostMsg.Transcript(turns) => turns }.flatten
          yield assertTrue(
            msgs.exists {
              case HostMsg.Transcript(_) => true
              case _                     => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _: HostMsg.AgentChunk  => true
              case _                      => false
            },
            snap.exists(t => t.user.exists(_.text == "hello from disk") && t.agent.contains("welcome back")),
            model.turns.exists(t => t.user.exists(_.text == "hello from disk") && t.agent.contains("welcome back")),
            model.todos.map(_.content) == List("Replay the disk snapshot", "Continue the work"),
            model.todos.map(_.status) == List(Todos.Completed, Todos.InProgress),
          )
        }
      },
      test("a live plan update replaces todos") {
        chat() { (rt, posted) =>
          for
            _ <- rt.ready
            _ <- posted.set(Nil)
            _ <- rt.ingestData(
              Ndjson.encode(
                Rpc.toLine(
                  Rpc.notifyOf(
                    "session/update",
                    AcpSessionNotify(
                      "sess_test",
                      AcpUpdate.Plan(
                        List(
                          TodoEntry("Checkout branch", Todos.InProgress, "medium"),
                          TodoEntry("Write tests", Todos.Pending, "high"),
                        )
                      ),
                    ),
                  )
                )
              )
            )
            msgs <- posted.get
            model = msgs.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
          yield assertTrue(
            msgs.contains(
              HostMsg.Todos(
                List(
                  TodoEntry("Checkout branch", Todos.InProgress, "medium"),
                  TodoEntry("Write tests", Todos.Pending, "high"),
                )
              )
            ),
            model.todos.map(_.content) == List("Checkout branch", "Write tests"),
          )
        }
      },
      test("session/load falls back to plan.json when ACP sent no plan") {
        delayedLoad().flatMap { case (transport, held) =>
          chat(
            transport = transport,
            planOnDisk = id => if id == "sess_disk" then List(TodoEntry("From disk", Todos.Pending, "medium")) else Nil,
          ) { (rt, posted) =>
            for
              _    <- rt.ready
              _    <- posted.set(Nil)
              _    <- rt.resumeSession("sess_disk")
              _    <- rt.ingestData(held.disk)
              msgs <- posted.get
              model = msgs.foldLeft(ChatModel.empty)(ChatModel.applyMsg)
            yield assertTrue(model.todos.map(_.content) == List("From disk"))
          }
        }
      },
      test("locked session/load posts SessionLocked and leaves the current session") {
        chat(transport = AcpTransport.fake(FakeAgent(lockLoad = true))) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.resumeSession("sess_live")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.SessionLocked("sess_live", msg) => msg.contains("TUI")
              case _                                       => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("newSession deletes an unused session this process created") {
        val deleted = scala.collection.mutable.ListBuffer.empty[SessionId]
        chat(scheduleEmptyDelete = deleted += _) { (rt, _) =>
          rt.ready *> rt.newSession.as(assertTrue(deleted.contains("sess_test")))
        }
      },
      test("newSession keeps a session that already has a prompt") {
        val deleted = scala.collection.mutable.ListBuffer.empty[SessionId]
        chat(scheduleEmptyDelete = deleted += _) { (rt, _) =>
          for
            _ <- rt.ready
            _ <- rt.send("hello")
            _ <- ZIO.succeed(deleted.clear())
            _ <- rt.newSession
          yield assertTrue(deleted.isEmpty)
        }
      },
      test("setEffort writes session/set_model with reasoningEffort") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- ZIO.succeed(lines.clear())
            _    <- rt.setEffort("xhigh")
            msgs <- posted.get
          yield assertTrue(
            lines.exists(l => l.contains("session/set_model") && l.contains("reasoningEffort") && l.contains("xhigh")),
            msgs.exists {
              case m: HostMsg.SessionMeta => m.effort == "xhigh" && m.modelId == "grok-4.6"
              case _                      => false
            },
          )
        }
      },
      test("/effort high sets effort on the current model") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/effort high")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.effort == "high"
              case _                      => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("unknown /effort posts an error and does not prompt") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/effort nope")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.contains("nope")
              case _                         => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("/effort on a model without reasoning posts an error") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.setModel("grok-code-fast-1")
            _    <- posted.set(Nil)
            _    <- rt.send("/effort high")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.contains("does not support")
              case _                         => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("/model grok-4.6 high sets model and effort") {
        val lines = scala.collection.mutable.ListBuffer.empty[String]
        val wrap  = AcpTransport.tap(AcpTransport.fake(), lines += _)
        chat(transport = wrap) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.setModel("grok-code-fast-1")
            _    <- posted.set(Nil)
            _    <- ZIO.succeed(lines.clear())
            _    <- rt.send("/model grok-4.6 xhigh")
            msgs <- posted.get
          yield assertTrue(
            lines.exists(l => l.contains("session/set_model") && l.contains("grok-4.6") && l.contains("xhigh")),
            msgs.exists {
              case m: HostMsg.SessionMeta => m.modelId == "grok-4.6" && m.effort == "xhigh"
              case _                      => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("setModel to a non-reasoning model clears effort") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.setEffort("high")
            _    <- posted.set(Nil)
            _    <- rt.setModel("grok-code-fast-1")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.modelId == "grok-code-fast-1" && m.effort.isEmpty
              case _                      => false
            }
          )
        }
      },
      test("/model in the composer switches by id or display name") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/model Grok Code Fast")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.modelId == "grok-code-fast-1"
              case _                      => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "/model Grok Code Fast", _, _) => true
              case _                                                     => false
            },
          )
        }
      },
      test("unknown /model posts an error and does not prompt") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/model nope")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.Error(message, _) => message.contains("nope")
              case _                         => false
            },
            !msgs.exists {
              case _: HostMsg.UserMessage => true
              case _                      => false
            },
          )
        }
      },
      test("/new in the composer starts a new session") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/new")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case HostMsg.ClearTranscript => true
              case _                       => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "/new", _, _) => true
              case _                                    => false
            },
          )
        }
      },
      test("setSetting posts the patched settings") {
        chat() { (rt, posted) =>
          rt.setSetting("useCtrlEnterToSend", true) *> posted.get.map { msgs =>
            assertTrue(msgs.exists {
              case s: HostMsg.Settings => s.useCtrlEnterToSend
              case _                   => false
            })
          }
        }
      },
      test("renameSession posts the new title on sessionMeta and the list") {
        var rows = List(SessionRow("sess_test", "Old"))
        chat(
          listSessions = () => rows,
          renameOnDisk = (id, op) =>
            op match
              case RenameOp.Manual(t) =>
                rows = rows.map(r => if r.id == id then r.copy(title = t) else r)
                rows.find(_.id == id)
              case RenameOp.Auto => rows.find(_.id == id),
        ) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.renameSession("sess_test", RenameOp.Manual("Plan"))
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.title == "Plan"
              case _                      => false
            },
            msgs.exists {
              case HostMsg.SessionList(sessions, _, _) =>
                sessions.exists(r => r.id == "sess_test" && r.title == "Plan")
              case _ => false
            },
          )
        }
      },
      test("send /rename applies a manual title and is not a prompt") {
        var rows = List(SessionRow("sess_test", "Old"))
        chat(
          listSessions = () => rows,
          renameOnDisk = (id, op) =>
            op match
              case RenameOp.Manual(t) =>
                rows = rows.map(r => if r.id == id then r.copy(title = t) else r)
                rows.find(_.id == id)
              case RenameOp.Auto => rows.find(_.id == id),
        ) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/rename Plan")
            msgs <- posted.get
          yield assertTrue(
            msgs.exists {
              case m: HostMsg.SessionMeta => m.title == "Plan"
              case _                      => false
            },
            !msgs.exists {
              case HostMsg.UserMessage(_, "/rename Plan", _, _) => true
              case _                                            => false
            },
          )
        }
      },
      test("send /delete does not wipe without confirm") {
        var deleted = false
        chat(
          deleteOnDisk = _ =>
            deleted = true; true
        ) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/delete")
            msgs <- posted.get
          yield assertTrue(
            !deleted,
            !msgs.exists {
              case HostMsg.ClearTranscript => true
              case _                       => false
            },
          )
        }
      },
      test("send /copy does not prompt") {
        chat() { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.send("/copy")
            msgs <- posted.get
          yield assertTrue(!msgs.exists {
            case _: HostMsg.UserMessage => true
            case _                      => false
          })
        }
      },
      test("copyOut posts Copied with clipboard text") {
        chat() { (rt, posted) =>
          for
            _    <- rt.copyOut("**hi**", None, backup = true, conversation = false)
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.Copied("Copied!", Some("**hi**")) => true
            case _                                         => false
          })
        }
      },
      test("copyOut to a path toasts the file") {
        chat() { (rt, posted) =>
          for
            _    <- rt.copyOut("# chat\n", Some("out.md"), backup = false, conversation = true)
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.Copied("Conversation exported to out.md", None) => true
            case _                                                       => false
          })
        }
      },
      test("questionSubmit replies with option ids and free text") {
        val written = scala.collection.mutable.ListBuffer.empty[String]
        chat(transport =
          AcpTransport.tap(
            AcpTransport.fake(),
            s =>
              written += s; (),
          )
        ) { (rt, posted) =>
          val ask = Ndjson.encode(
            Rpc.toLine(
              Rpc.request(
                RpcId.Str("q-1"),
                "_x.ai/ask_user_question",
                AskUserQuestionParams(
                  List(
                    AgentQuestion(
                      "style",
                      "How?",
                      List(QuestionOption("dense", "Dense")),
                      allowMultiple = true,
                      allowFreeText = true,
                    )
                  )
                ),
              )
            )
          )
          for
            _    <- rt.ready
            _    <- posted.set(Nil)
            _    <- rt.ingestData(ask)
            card <- posted.get
            _    <- rt.questionSubmit("q-1", List(QuestionAnswer("style", List("dense"), Some("notes"))))
            blob = written.mkString
          yield assertTrue(
            card.exists {
              case HostMsg.Question("q-1", qs) =>
                qs.headOption.exists(q => q.allowMultiple && q.allowFreeText)
              case _ => false
            },
            blob.contains("\"answers\""),
            blob.contains("dense"),
            blob.contains("notes"),
          )
          end for
        }
      },
      test("questionDismiss replies with empty answers") {
        val written = scala.collection.mutable.ListBuffer.empty[String]
        chat(transport =
          AcpTransport.tap(
            AcpTransport.fake(),
            s =>
              written += s; (),
          )
        ) { (rt, _) =>
          val ask = Ndjson.encode(
            Rpc.toLine(
              Rpc.request(
                RpcId.Str("q-2"),
                "_x.ai/ask_user_question",
                AskUserQuestionParams(List(AgentQuestion("style", "How?", List(QuestionOption("dense", "Dense"))))),
              )
            )
          )
          for
            _ <- rt.ready
            _ <- rt.ingestData(ask)
            _ <- rt.questionDismiss("q-2")
            blob = written.mkString
          yield assertTrue(blob.contains("q-2"), blob.contains("\"answers\""), blob.contains("[]"))
        }
      },
      test("deleteSession of another id refreshes the open picker") {
        var rows = List(SessionRow("keep", "Keep", activityMs = 2), SessionRow("gone", "Gone", activityMs = 1))
        chat(
          listSessions = () => rows,
          deleteOnDisk = id =>
            rows = rows.filterNot(_.id == id)
            true,
        ) { (rt, posted) =>
          for
            _    <- rt.ready
            _    <- rt.openPicker
            _    <- posted.set(Nil)
            _    <- rt.deleteSession("gone")
            msgs <- posted.get
          yield assertTrue(msgs.exists {
            case HostMsg.SessionList(sessions, _, true) => sessions.map(_.id) == List("keep")
            case _                                      => false
          })
        }
      },
    )

  final class HeldLoad(var disk: String = "", var live: String = "")
  final class HeldNew(var get: String = "")

  def delayedLoad(): UIO[(AcpTransport, HeldLoad)] =
    ZIO.succeed {
      val held                        = HeldLoad()
      var ingest: String => UIO[Unit] = _ => ZIO.unit
      val agent                       = FakeAgent()
      val transport                   = new AcpTransport:
        def attach(next: String => UIO[Unit]): UIO[Unit] = ZIO.succeed { ingest = next }
        def write(data: String): BeardError.Result[Unit] =
          val (lines, _) = Ndjson.split("", data)
          ZIO.foreachDiscard(lines) { line =>
            Rpc.parse(line) match
              case Right(Rpc.Request(id, "session/load", params)) =>
                ZIO.succeed {
                  val sid    = params.as[SessionLoadParams].toOption.map(_.sessionId).getOrElse(SessionId.empty)
                  val ndjson =
                    if sid == SessionId("sess_disk") then
                      ChatRuntimeSpec.loadReplay(id, sid, "hello from disk", "welcome back")
                    else ChatRuntimeSpec.loadReplay(id, sid, "hello from live", "live agent")
                  if sid == SessionId("sess_disk") then held.disk = ndjson else held.live = ndjson
                }
              case Right(msg) => ingest(agent.encodeReplies(msg))
              case Left(_)    => ZIO.unit
          }
        end write
        def close: UIO[Unit] = ZIO.unit
      (transport, held)
    }

  def delayedNew(): UIO[(AcpTransport, HeldNew)] =
    ZIO.succeed {
      val held                        = HeldNew()
      var ingest: String => UIO[Unit] = _ => ZIO.unit
      val agent                       = FakeAgent()
      val transport                   = new AcpTransport:
        def attach(next: String => UIO[Unit]): UIO[Unit] = ZIO.succeed { ingest = next }
        def write(data: String): BeardError.Result[Unit] =
          val (lines, _) = Ndjson.split("", data)
          ZIO.foreachDiscard(lines) { line =>
            Rpc.parse(line) match
              case Right(msg @ Rpc.Request(_, "session/new", _)) =>
                ZIO.succeed { held.get = agent.encodeReplies(msg) }
              case Right(msg) => ingest(agent.encodeReplies(msg))
              case Left(_)    => ZIO.unit
          }
        def close: UIO[Unit] = ZIO.unit
      (transport, held)
    }

  def chat(
      transport: AcpTransport = AcpTransport.fake(),
      searchFiles: String => List[MentionFile] = _ => Nil,
      listSessions: () => List[SessionRow] = () => Nil,
      scheduleEmptyDelete: SessionId => Unit = _ => (),
      renameOnDisk: (SessionId, RenameOp) => Option[SessionRow] = (_, _) => None,
      deleteOnDisk: SessionId => Boolean = _ => false,
      persistChanges: List[ChangeSet] => UIO[Unit] = _ => ZIO.unit,
      readDisk: String => Option[String] = _ => None,
      followFile: (String, Option[Int]) => Unit = (_, _) => (),
      planOnDisk: SessionId => List[TodoEntry] = _ => Nil,
      beforeInitialize: UIO[Unit] = ZIO.unit,
  )(body: (ChatRuntime, Ref[List[HostMsg]]) => UIO[TestResult]): UIO[TestResult] =
    ZIO.scoped {
      for
        posted <- Ref.make(List.empty[HostMsg])
        rt     <- ChatRuntime
          .make(transport = transport, beforeInitialize = beforeInitialize)
          .provideSome[Scope](
            ChatEnv.test(
              post = msg => posted.update(_ :+ msg),
              searchFiles = searchFiles,
              listSessions = listSessions,
              scheduleEmptyDelete = scheduleEmptyDelete,
              renameOnDisk = renameOnDisk,
              deleteOnDisk = deleteOnDisk,
              persistChanges = persistChanges,
              readDisk = readDisk,
              followFile = followFile,
              planOnDisk = planOnDisk,
            )
          )
        result <- body(rt, posted)
      yield result
    }

  def loadReplay(id: RpcId, sessionId: SessionId, user: String, agent: String): String =
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
