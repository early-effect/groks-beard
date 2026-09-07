package groksbeard.core

import zio.*
import zio.json.*
import zio.test.*

object TerminalsSpec extends ZIOSpecDefault:
  def spec =
    suite("Terminals")(
      test("mint is sequential and fromWire rejects empty") {
        val ids  = TerminalSeq()
        val a    = ids.next()
        val b    = ids.next()
        val json = TerminalCreateResult(a).toJson
        assertTrue(
          a.value == "term-1",
          b.value == "term-2",
          a != b,
          TerminalId.fromWire("").isEmpty,
          TerminalId.fromWire("  ").isEmpty,
          TerminalId.fromWire("term-1").contains(a),
          json.contains("\"terminalId\":\"term-1\""),
          json.fromJson[TerminalCreateResult] == Right(TerminalCreateResult(a)),
        )
      },
      test("test layer create then output wait kill release") {
        ZIO.scoped {
          (for
            terms  <- ZIO.service[Terminals]
            id     <- terms.create("echo", List("hi"), None, Nil, None)
            out    <- terms.output(id)
            st     <- terms.waitForExit(id)
            killed <- terms.kill(id)
            gone   <- terms.release(id)
            miss   <- terms.output(id)
          yield assertTrue(
            id.value == "term-1",
            out.exists(_.output.contains("echo hi")),
            st.contains(TerminalExitStatus(Some(0), None)),
            killed,
            gone,
            miss.isEmpty,
          )).provide(Terminals.test())
        }
      },
      test("capTail drops a prefix to stay under the byte cap") {
        val (kept, cut) = Terminals.capTail("abcdef", 3)
        assertTrue(cut, kept == "def", Utf8.byteLength(kept) == 3)
      },
      test("argv wraps a script under $SHELL, not /bin/sh") {
        val zsh = "/bin/zsh"
        assertTrue(
          TerminalShell.argv("echo", List("hi"), zsh) == List("echo", "hi"),
          TerminalShell.argv("echo hi\npwd", Nil, zsh) == List(zsh, "-c", "echo hi\npwd"),
          TerminalShell.argv("/bin/bash -lc 'echo hi'", Nil, zsh) == List(zsh, "-c", "echo hi"),
          TerminalShell.argv("/bin/bash", List("-lc", "pwd"), zsh) == List(zsh, "-c", "pwd"),
        )
      },
      test("unwrap peels grok's login-bash wrapper and refuses other flags") {
        assertTrue(
          TerminalShell.unwrapGrokBashLoginWrapper("/bin/bash -lc ls").contains("ls"),
          TerminalShell.unwrapGrokBashLoginWrapper("/bin/bash -lc 'echo hi'").contains("echo hi"),
          TerminalShell.unwrapGrokBashLoginWrapper("bash -n -l -c 'rm target'").isEmpty,
          TerminalShell.unwrapGrokBashLoginWrapper("/bin/bash -lc rm -rf target").isEmpty,
          TerminalShell.posixShellFromEnv(Some("/opt/homebrew/bin/fish")).isEmpty,
          TerminalShell.posixShellFromEnv(Some("/bin/zsh")).contains("/bin/zsh"),
          TerminalShell.grokShellEnv("/bin/zsh").contains("/bin/zsh"),
          TerminalShell.grokShellEnv("/bin/sh").isEmpty,
        )
      },
    )
end TerminalsSpec
