package groksbeard.core

import zio.test.*

object TranscriptCopySpec extends ZIOSpecDefault:
  def spec =
    suite("TranscriptCopy")(
      test("parseCopy reads optional nth then path") {
        assertTrue(
          TranscriptCopy.parseCopy("") == Right(CopySpec()),
          TranscriptCopy.parseCopy("2") == Right(CopySpec(2)),
          TranscriptCopy.parseCopy("out.txt") == Right(CopySpec(path = Some("out.txt"))),
          TranscriptCopy.parseCopy("2 ~/exports/last-reply.md") ==
            Right(CopySpec(2, Some("~/exports/last-reply.md"))),
          TranscriptCopy.parseCopy("0").isLeft,
        )
      },
      test("copy picks the nth-latest non-empty assistant markdown") {
        val turns = List(
          TurnView("t1", user = Some(TurnUser("one")), agent = "first"),
          TurnView("t2", user = Some(TurnUser("two")), agent = "  "),
          TurnView("t3", user = Some(TurnUser("three")), agent = "latest"),
        )
        assertTrue(
          TranscriptCopy.copy(turns, "") == Right(CopyJob("latest")),
          TranscriptCopy.copy(turns, "2") == Right(CopyJob("first")),
          TranscriptCopy.copy(turns, "3").isLeft,
          TranscriptCopy.copy(Nil, "").isLeft,
        )
      },
      test("export joins user and assistant blocks") {
        val turns = List(
          TurnView("t1", user = Some(TurnUser("hello")), agent = "hi"),
          TurnView("t2", user = Some(TurnUser("again")), agent = "ok"),
        )
        val body = TranscriptCopy.conversationJob(turns, inSession = true, "").toOption.get.text
        assertTrue(
          body.contains("## User\nhello"),
          body.contains("## Assistant\nhi"),
          body.contains("## User\nagain"),
          TranscriptCopy.conversationJob(Nil, inSession = false, "").isLeft,
          TranscriptCopy.conversationJob(Nil, inSession = true, "out.md").isLeft,
        )
      },
      test("expandPath resolves ~, absolute, and cwd-relative") {
        assertTrue(
          TranscriptCopy.expandPath("~", "/Users/russ", "/repo") == "/Users/russ",
          TranscriptCopy.expandPath("~/exports/a.md", "/Users/russ", "/repo") == "/Users/russ/exports/a.md",
          TranscriptCopy.expandPath("/tmp/a.md", "/Users/russ", "/repo") == "/tmp/a.md",
          TranscriptCopy.expandPath("out.txt", "/Users/russ", "/repo") == "/repo/out.txt",
        )
      },
      test("backupPath prefers GROK_COPY_FILE") {
        val env = Map("GROK_COPY_FILE" -> "/tmp/copy.txt", "HOME" -> "/Users/russ")
        assertTrue(
          TranscriptCopy.backupPath(env.get, "/Users/russ/.grok", "/repo") == "/tmp/copy.txt",
          TranscriptCopy.backupPath(_ => None, "/Users/russ/.grok", "/repo") ==
            "/Users/russ/.grok/last-copy.txt",
        )
      },
      test("toast matches TUI copy and export") {
        assertTrue(
          TranscriptCopy.toast(None, conversation = false) == "Copied!",
          TranscriptCopy.toast(Some("out.md"), conversation = false) == "Copied to out.md",
          TranscriptCopy.toast(None, conversation = true) == "Conversation copied to clipboard",
          TranscriptCopy.toast(Some("out.md"), conversation = true) == "Conversation exported to out.md",
        )
      },
    )
end TranscriptCopySpec
