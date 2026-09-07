package groksbeard.core

import zio.test.*

object ToolOutputSpec extends ZIOSpecDefault:
  def spec =
    suite("ToolOutput")(
      test("empty prev takes the chunk") {
        assertTrue(ToolOutput.pull("", "line-1\n") == "line-1\n")
      },
      test("a snapshot that grew replaces") {
        val prev = "line-1\n"
        val more = "line-1\nline-2\n"
        assertTrue(ToolOutput.pull(prev, more) == more)
      },
      test("a delta appends") {
        assertTrue(ToolOutput.pull("line-1\n", "line-2\n") == "line-1\nline-2\n")
      },
      test("a stale shorter snapshot is ignored") {
        val prev = "line-1\nline-2\n"
        assertTrue(ToolOutput.pull(prev, "line-1\n", snapshot = true) == prev)
      },
      test("a completed snapshot replaces a live fragment that is not a prefix") {
        val live = "beard-terminal-probe\n/tmp"
        val done = "beard-terminal-probe\n/Users/russ/projects/fun/groks-beard\nDarwin\n"
        assertTrue(ToolOutput.pull(live, done, snapshot = true) == done)
      },
    )
end ToolOutputSpec
