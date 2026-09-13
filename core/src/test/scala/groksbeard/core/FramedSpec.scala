package groksbeard.core

import zio.test.*

object FramedSpec extends ZIOSpecDefault:
  def spec =
    suite("FrameState")(
      test("splitNdjson keeps a partial trailing line") {
        val (lines, rest) = Ndjson.split("{\"a\":", "1}\n{\"b\":2}\n{\"c\":")
        assertTrue(lines == List("""{"a":1}""", """{"b":2}"""), rest == """{"c":""")
      },
      test("encodes two JSON-RPC lines in one stdout chunk") {
        val fake   = FakeAgent(pairSetModeWithTerminal = true)
        val req    = Rpc.request(RpcId.Num(7), "session/set_mode", SessionSetModeParams("sess_test", "plan"))
        val text   = fake.encodeReplies(req)
        val lines  = text.trim.split("\n").toList
        val first  = Rpc.parse(lines.head)
        val second = Rpc.parse(lines(1))
        assertTrue(
          lines.size == 2,
          first.exists {
            case Rpc.Response(id, _, _) => id == RpcId.Num(7)
            case _                      => false
          },
          second.exists {
            case Rpc.Request(_, method, _) => method == "terminal/create"
            case _                         => false
          },
        )
      },
      test("feed commits session/set_mode before returning later lines") {
        val fake = FakeAgent(pairSetModeWithTerminal = true)
        val req  = Rpc.request(RpcId.Num(7), "session/set_mode", SessionSetModeParams("sess_test", "plan"))
        val sent = FrameState.recordOutgoing(FrameState.empty, req)
        val (next, msgs) = FrameState.feed(sent, fake.encodeReplies(req))
        val sawCreate    = msgs.exists {
          case Rpc.Request(_, "terminal/create", _) => true
          case _                                    => false
        }
        assertTrue(sawCreate, next.modeId.contains(ModeId.Plan), next.planActive)
      },
      test("session/load lock is a JSON-RPC error") {
        val fake  = FakeAgent(lockLoad = true)
        val req   = Rpc.request(RpcId.Num(1), "session/load", SessionLoadParams("sess_test"))
        val reply = fake.replies(req).head
        assertTrue(
          reply match
            case Rpc.Response(_, _, Some(err)) => err.code == Rpc.MethodNotFound
            case _                             => false
        )
      },
      test("unknown methods are -32601") {
        val fake  = FakeAgent()
        val req   = Rpc.Request(RpcId.Num(1), "_x.ai/not-a-method", zio.json.ast.Json.Obj())
        val reply = fake.replies(req).head
        assertTrue(
          reply match
            case Rpc.Response(_, _, Some(err)) => err.code == -32601
            case _                             => false
        )
      },
    )
end FramedSpec
