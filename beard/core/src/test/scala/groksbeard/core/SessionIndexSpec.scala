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
      test("holdOrder keeps the given ids and appends newcomers by last used") {
        val rows = List(
          SessionRow("a", "A", activityMs = 1),
          SessionRow("b", "B", activityMs = 9),
          SessionRow("c", "C", activityMs = 5),
        )
        val held = SessionIndex.holdOrder(rows, List("c", "a"))
        val all  = SessionIndex.present(rows, None)
        val pin  = SessionIndex.present(rows, Some(List("c", "a")))
        assertTrue(
          held.map(_.id) == List("c", "a", "b"),
          all.map(_.id) == List("b", "c", "a"),
          pin.map(_.id) == List("c", "a", "b"),
        )
      },
      test("touchCurrent promotes the current id unless skipped") {
        val rows = List(
          SessionRow("old", "Earlier", activityMs = 9),
          SessionRow("cur", "Current", activityMs = 1),
        )
        val promoted = SessionIndex.touchCurrent(rows, "cur", 100, skip = false)
        val skipped  = SessionIndex.touchCurrent(rows, "cur", 100, skip = true)
        assertTrue(
          promoted.map(_.id) == List("cur", "old"),
          promoted.head.activityMs == 100,
          skipped.map(_.id) == List("old", "cur"),
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
      test("opaque ids are Untitled session unless a last turn exists") {
        val id     = "01a06d3c-7845-7793-a7c6-fdc8ca2d2a1e"
        val onlyId = SessionSummary(SessionInfo(id, "/repo"))
        val turn   = onlyId.copy(last_turn_summary = Some("Fix the picker"))
        val row    = SessionRow(id, id, lastTurn = Some("hello from disk"))
        assertTrue(
          SessionSummary.title(onlyId) == "Untitled session",
          SessionSummary.title(turn) == "Fix the picker",
          SessionIndex.displayTitle(row) == "hello from disk",
          SessionIndex.isOpaqueId(id, id),
        )
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
      test("dedupEmpty keeps the newest unused draft and every session with messages") {
        val rows = List(
          SessionRow("used", "Real work", activityMs = 9, messages = Some(4)),
          SessionRow("fresh-empty", "Untitled session", activityMs = 8, messages = Some(0)),
          SessionRow("old-empty", "Untitled session", activityMs = 7, messages = Some(0)),
          SessionRow("also-used", "Earlier", activityMs = 6, messages = Some(2)),
        )
        assertTrue(SessionIndex.dedupEmpty(rows).map(_.id) == List("used", "fresh-empty", "also-used"))
      },
      test("listRows drops older empty drafts so titled sessions fill the page") {
        val fs = MemorySessionFs(
          Map(
            "/home/sessions/%2Frepo"                   -> Dir,
            "/home/sessions/%2Frepo/used"              -> Dir,
            "/home/sessions/%2Frepo/empty-new"         -> Dir,
            "/home/sessions/%2Frepo/empty-old"         -> Dir,
            "/home/sessions/%2Frepo/used/summary.json" -> File(
              9,
              """{"info":{"id":"used","cwd":"/repo"},"generated_title":"Real work","num_messages":4,"last_active_at":"2026-09-04T15:00:00Z"}""",
            ),
            "/home/sessions/%2Frepo/empty-new/summary.json" -> File(
              8,
              """{"info":{"id":"empty-new","cwd":"/repo"},"num_messages":0,"last_active_at":"2026-09-04T14:00:00Z"}""",
            ),
            "/home/sessions/%2Frepo/empty-old/summary.json" -> File(
              1,
              """{"info":{"id":"empty-old","cwd":"/repo"},"num_messages":0,"last_active_at":"2026-09-01T00:00:00Z"}""",
            ),
          )
        )
        val rows = SessionIndex.listRows(fs, "/home", "/repo")
        assertTrue(rows.map(_.id) == List("used", "empty-new"))
      },
      test("listRows prefers last_active_at over newer files and newer ids") {
        val fs = MemorySessionFs(
          Map(
            "/home/sessions/%2Frepo"                   -> Dir,
            "/home/sessions/%2Frepo/old"               -> Dir,
            "/home/sessions/%2Frepo/neu"               -> Dir,
            "/home/sessions/%2Frepo/old/updates.jsonl" -> File(1, ""),
            "/home/sessions/%2Frepo/neu/updates.jsonl" -> File(99, ""),
            "/home/sessions/%2Frepo/old/summary.json"  -> File(
              1,
              """{"info":{"id":"old","cwd":"/repo"},"generated_title":"Used today","created_at":"2026-08-01T00:00:00Z","last_active_at":"2026-09-04T15:00:00Z"}""",
            ),
            "/home/sessions/%2Frepo/neu/summary.json" -> File(
              99,
              """{"info":{"id":"neu","cwd":"/repo"},"generated_title":"Created later","created_at":"2026-09-04T14:00:00Z"}""",
            ),
          )
        )
        val rows = SessionIndex.listRows(fs, "/home", "/repo")
        assertTrue(
          rows.map(_.id) == List("old", "neu"),
          SessionSummary.epochMs("2026-09-04T15:00:00Z").exists(_ > 1_000_000_000_000L),
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
          merged.exists(_.name == "model"),
          SessionCommands.intercept("/new").contains(ClientCommand("new")),
          SessionCommands.intercept("/resume").contains(ClientCommand("resume")),
          SessionCommands.intercept("/model grok-4.6").contains(ClientCommand("model", "grok-4.6")),
          SessionCommands.intercept("/m").contains(ClientCommand("m")),
          SessionCommands.intercept("/rename Plan").contains(ClientCommand("rename", "Plan")),
          SessionCommands.intercept("/title").contains(ClientCommand("title")),
          SessionCommands.intercept("/delete").contains(ClientCommand("delete")),
          merged.exists(_.name == "rename"),
          merged.exists(_.name == "delete"),
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
