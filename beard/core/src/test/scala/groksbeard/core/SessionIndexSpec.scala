package groksbeard.core

import zio.test.*

object SessionIndexSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionIndex")(
      test("encodes cwd the way Grok groups sessions") {
        assertTrue(
          SessionIndex.encodeCwd("/Users/russ/projects/fun/groks-beard") ==
            "%2FUsers%2Fruss%2Fprojects%2Ffun%2Fgroks-beard"
        )
      },
      test("encodeCwd percent-encodes every reserved byte") {
        val encoded = SessionIndex.encodeCwd("/tmp/has space+plus")
        assertTrue(
          !encoded.contains("/"),
          !encoded.contains(" "),
          encoded.contains("%2F"),
          encoded.contains("%20"),
        )
      },
      test("flags encoded names over 255 bytes") {
        assertTrue(
          !SessionIndex.encodedCwdExceedsLimit("short"),
          SessionIndex.encodedCwdExceedsLimit("x" * 300),
        )
      },
      test("orders sessions by updates.jsonl mtime, not id") {
        val ordered = SessionIndex.index(
          List(
            SessionActivity("old-uuid", updatesMtimeMs = Some(1)),
            SessionActivity("new-uuid", updatesMtimeMs = Some(9)),
            SessionActivity("no-updates", summaryMtimeMs = Some(5)),
          )
        )
        assertTrue(ordered.map(_.id) == List("new-uuid", "no-updates", "old-uuid"))
      },
      test("filter is case-insensitive over title, id, and summary") {
        val rows = List(
          SessionRow("abc", "Effect-TS plan", summary = Some("VS Code plugin")),
          SessionRow("zzz", "Unrelated"),
        )
        assertTrue(
          SessionIndex.filter(rows, "effect").map(_.id) == List("abc"),
          SessionIndex.filter(rows, "ABC").map(_.id) == List("abc"),
          SessionIndex.filter(rows, "plugin").map(_.id) == List("abc"),
          SessionIndex.filter(rows, "").size == 2,
        )
      },
      test("title prefers generated_title unless the user renamed") {
        val auto = SessionSummary(
          SessionInfo("id1", "/repo"),
          session_summary = Some("vague"),
          generated_title = Some("Real title"),
        )
        val manual = auto.copy(title_is_manual = Some(true), session_summary = Some("My name"))
        assertTrue(SessionSummary.title(auto) == "Real title", SessionSummary.title(manual) == "My name")
      },
      test("summary.json extra keys still decode") {
        val json =
          """{"info":{"id":"id1","cwd":"/repo"},"generated_title":"Hello","chat_format_version":2,"head_branch":"main"}"""
        val got = SessionSummary.decode(json)
        assertTrue(got.exists(s => SessionSummary.title(s) == "Hello" && s.info.id == "id1"))
      },
      test("EmptySessionTracker deletes only unused sessions this process created") {
        val t = EmptySessionTracker()
        t.markCreated("a")
        t.markCreated("b")
        t.markHasHistory("b")
        assertTrue(t.shouldDelete("a"), !t.shouldDelete("b"), !t.shouldDelete("tui-made"))
      },
      test("SessionLoad classifies lock copy from the error text") {
        assertTrue(
          SessionLoad.classify("session locked") == SessionLoadKind.Locked,
          SessionLoad.classify("busy") == SessionLoadKind.Locked,
          SessionLoad.classify("in use by TUI") == SessionLoadKind.Locked,
          SessionLoad.classify("no such session") == SessionLoadKind.Failed,
          SessionLoad.copy(SessionLoadKind.Locked) == "This session is open in the TUI",
        )
      },
      test("listRows uses a SessionFs and sorts by activity") {
        val fs = MemorySessionFs(
          Map(
            "/home/sessions/%2Frepo"                   -> Dir,
            "/home/sessions/%2Frepo/old"               -> Dir,
            "/home/sessions/%2Frepo/new"               -> Dir,
            "/home/sessions/%2Frepo/old/updates.jsonl" -> File(1, ""),
            "/home/sessions/%2Frepo/new/updates.jsonl" -> File(9, ""),
            "/home/sessions/%2Frepo/new/summary.json"  -> File(
              9,
              """{"info":{"id":"new","cwd":"/repo"},"generated_title":"Newest"}""",
            ),
          )
        )
        val rows = SessionIndex.listRows(fs, "/home", "/repo")
        assertTrue(rows.map(_.id) == List("new", "old"), rows.head.title == "Newest")
      },
      test("groupDirs includes a slug dir whose .cwd matches") {
        val fs = MemorySessionFs(
          Map(
            "/home/sessions/slug-hash"                 -> Dir,
            "/home/sessions/slug-hash/.cwd"            -> File(1, "/very/long/cwd"),
            "/home/sessions/slug-hash/s1"              -> Dir,
            "/home/sessions/slug-hash/s1/summary.json" -> File(
              2,
              """{"info":{"id":"s1","cwd":"/very/long/cwd"},"generated_title":"Hashed"}""",
            ),
          )
        )
        val rows = SessionIndex.listRows(fs, "/home", "/very/long/cwd")
        assertTrue(rows.map(_.id) == List("s1"), rows.head.title == "Hashed")
      },
      test("SessionCommands merge keeps advertised names first") {
        val advertised = List(SlashCommand("compact", "Compact context"))
        val merged     = SessionCommands.merge(advertised)
        assertTrue(
          merged.head.name == "compact",
          merged.exists(_.name == "new"),
          merged.exists(_.name == "resume"),
          SessionCommands.intercept("/new").contains("new"),
          SessionCommands.intercept("/resume").contains("resume"),
          SessionCommands.intercept("hello").isEmpty,
        )
      },
    )

  sealed trait Entry
  case object Dir                                  extends Entry
  final case class File(mtime: Long, text: String) extends Entry

  final class MemorySessionFs(files: Map[String, Entry]) extends SessionFs:
    def listNames(dir: String): List[String] =
      val prefix = if dir.endsWith("/") then dir else dir + "/"
      files.keys.iterator
        .filter(p => p.startsWith(prefix) && !p.substring(prefix.length).contains("/"))
        .map(_.substring(prefix.length))
        .toList
    def isDirectory(path: String): Boolean  = files.get(path).contains(Dir)
    def mtimeMs(path: String): Option[Long] =
      files.get(path).collect { case File(m, _) => m }
    def readText(path: String): Option[String] =
      files.get(path).collect { case File(_, t) => t }
  end MemorySessionFs
end SessionIndexSpec
