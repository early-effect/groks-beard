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
          case HostMsg.Ready                   => "ready"
          case HostMsg.SessionMeta(_, _, _, _) => "sessionMeta"
          case HostMsg.AvailableCommands(_)    => "availableCommands"
          case HostMsg.Settings(_)             => "settings"
          case other                           => other.toString
        }
        assertTrue(
          tags.contains("ready"),
          tags.contains("sessionMeta"),
          tags.contains("availableCommands"),
          posted.exists {
            case HostMsg.SessionMeta(_, _, "normal", modes) => modes.exists(_.id == "plan")
            case _                                          => false
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
          tags.contains("tool:Edit"),
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
          turn.tools.exists(_.title == "Edit"),
          turn.stopReason.contains("end_turn"),
          !ChatModel.turnIsRunning(model),
        )
      },
      test("send while a turn is running posts queued") {
        val posted               = scala.collection.mutable.ListBuffer.empty[HostMsg]
        lazy val rt: ChatRuntime = ChatRuntime { msg =>
          posted += msg
          msg match
            case HostMsg.UserMessage(_, _, _, _) => rt.send("later")
            case _                               => ()
        }
        rt.ready()
        posted.clear()
        rt.send("hello")
        assertTrue(posted.exists {
          case HostMsg.Queued(1) => true
          case _                 => false
        })
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
      test("setMode commits plan before later work") {
        val rt = ChatRuntime(_ => ())
        rt.ready()
        rt.setMode("plan")
        assertTrue(rt.state.modeId.contains("plan"), rt.state.planActive)
      },
    )
end ChatRuntimeSpec
