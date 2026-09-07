package groksbeard.core

import zio.*
import zio.test.*

object ProcessTerminalsSpec extends ZIOSpecDefault:
  def spec =
    suite("ProcessTerminals")(
      test("create echo captures stdout and waits for zero") {
        ZIO.scoped {
          (for
            terms <- ZIO.service[Terminals]
            id    <- terms.create("/bin/echo", List("beard-term"), None, Nil, None)
            st    <- terms.waitForExit(id)
            out   <- terms.output(id)
            _     <- terms.release(id)
          yield assertTrue(
            id.value == "term-1",
            st.contains(TerminalExitStatus(Some(0), None)),
            out.exists(_.output.contains("beard-term")),
            !out.exists(_.truncated),
          )).provide(ProcessTerminals.layer("."))
        }
      },
      test("create runs a multiline script through the shell") {
        ZIO.scoped {
          (for
            terms <- ZIO.service[Terminals]
            id    <- terms.create("echo beard-terminal-probe\npwd", Nil, None, Nil, None)
            st    <- terms.waitForExit(id)
            out   <- terms.output(id)
            _     <- terms.release(id)
          yield assertTrue(
            st.contains(TerminalExitStatus(Some(0), None)),
            out.exists(_.output.contains("beard-terminal-probe")),
          )).provide(ProcessTerminals.layer("."))
        }
      },
    )
end ProcessTerminalsSpec
