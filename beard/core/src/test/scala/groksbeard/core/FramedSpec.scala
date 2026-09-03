package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object FramedSpec extends ZIOSpecDefault:
  def spec =
    suite("Framed")(
      test("splitNdjson keeps a partial trailing line") {
        val (lines, rest) = Ndjson.split("{\"a\":", "1}\n{\"b\":2}\n{\"c\":")
        assertTrue(lines == List("""{"a":1}""", """{"b":2}"""), rest == """{"c":""")
      },
      test("encodes two JSON-RPC lines in one stdout chunk") {
        val fake = FakeAgent(pairSetModeWithTerminal = true)
        val req  = Rpc.Request(
          Json.Num(7),
          "session/set_mode",
          Json.Obj("sessionId" -> Json.Str("sess_test"), "modeId" -> Json.Str("plan")),
        )
        val text   = fake.encodeReplies(req)
        val lines  = text.trim.split("\n").toList
        val first  = Rpc.parse(lines.head)
        val second = Rpc.parse(lines(1))
        assertTrue(
          lines.size == 2,
          first.exists {
            case Rpc.Response(id, _, _) => id == Json.Num(7)
            case _                      => false
          },
          second.exists {
            case Rpc.Request(_, method, _) => method == "terminal/create"
            case _                         => false
          },
        )
      },
      test("commits session/set_mode before the next line in the same stdout chunk") {
        val framed = Framed(SessionState())
        val fake   = FakeAgent(pairSetModeWithTerminal = true)
        val req    = Rpc.Request(
          Json.Num(7),
          "session/set_mode",
          Json.Obj("sessionId" -> Json.Str("sess_test"), "modeId" -> Json.Str("plan")),
        )
        framed.recordOutgoing(req)
        var seen: Option[(String, Boolean)] = None
        framed.feed(fake.encodeReplies(req)).foreach {
          case Rpc.Request(_, "terminal/create", _) =>
            seen = Some((framed.state.modeId.getOrElse("unset"), framed.state.planActive))
          case _ => ()
        }
        assertTrue(seen.contains(("plan", true)), framed.state.planActive)
      },
      test("session/load lock is a JSON-RPC error") {
        val fake  = FakeAgent(lockLoad = true)
        val req   = Rpc.Request(Json.Num(1), "session/load", Json.Obj("sessionId" -> Json.Str("sess_test")))
        val reply = fake.replies(req).head
        assertTrue(
          reply match
            case Rpc.Response(_, _, Some(err)) => err.code == Rpc.MethodNotFound
            case _                             => false
        )
      },
      test("unknown methods are -32601") {
        val fake  = FakeAgent()
        val req   = Rpc.Request(Json.Num(1), "_x.ai/not-a-method", Json.Obj())
        val reply = fake.replies(req).head
        assertTrue(
          reply match
            case Rpc.Response(_, _, Some(err)) => err.code == -32601
            case _                             => false
        )
      },
    )
end FramedSpec
