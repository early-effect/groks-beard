package groksbeard.core

import zio.*
import zio.test.*

object SessionEditSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionEdit")(
      test("parseRename treats --auto as unpin and rejects extra args") {
        assertTrue(
          SessionEdit.parseRename("Plan") == Right(RenameOp.Manual("Plan")),
          SessionEdit.parseRename("  --auto  ") == Right(RenameOp.Auto),
          SessionEdit.parseRename("").isLeft,
          SessionEdit.parseRename("--auto extra").isLeft,
        )
      },
      test("patchManual keeps unknown summary keys") {
        val raw =
          """{"info":{"id":"s1","cwd":"/repo"},"generated_title":"Old","chat_format_version":2,"head_branch":"main"}"""
        val next = SessionEdit.patchManual(raw, "Plan").get
        assertTrue(
          next.contains("\"session_summary\":\"Plan\""),
          next.contains("\"title_is_manual\":true"),
          next.contains("\"chat_format_version\":2"),
          next.contains("\"head_branch\":\"main\""),
          next.contains("\"generated_title\":\"Old\""),
        )
      },
      test("rename writes a manual title onto summary.json") {
        val fs = MemoryFs(
          Map(
            "/home/sessions/%2Frepo"                 -> Dir,
            "/home/sessions/%2Frepo/s1"              -> Dir,
            "/home/sessions/%2Frepo/s1/summary.json" -> File(
              1,
              """{"info":{"id":"s1","cwd":"/repo"},"generated_title":"Old","extra":true}""",
            ),
          )
        )
        for
          row <- SessionEdit.rename(fs, "/home", "/repo", "s1", RenameOp.Manual("Plan"))
          raw <- fs.readText("/home/sessions/%2Frepo/s1/summary.json")
        yield assertTrue(
          row.exists(_.title == "Plan"),
          raw.exists(_.contains("\"session_summary\":\"Plan\"")),
          raw.exists(_.contains("\"title_is_manual\":true")),
          raw.exists(_.contains("\"extra\":true")),
        )
      },
      test("rename --auto unpins a manual title") {
        val fs = MemoryFs(
          Map(
            "/home/sessions/%2Frepo"                 -> Dir,
            "/home/sessions/%2Frepo/s1"              -> Dir,
            "/home/sessions/%2Frepo/s1/summary.json" -> File(
              1,
              """{"info":{"id":"s1","cwd":"/repo"},"generated_title":"Real title","session_summary":"Mine","title_is_manual":true}""",
            ),
          )
        )
        SessionEdit.rename(fs, "/home", "/repo", "s1", RenameOp.Auto).map { row =>
          assertTrue(row.exists(_.title == "Real title"))
        }
      },
      test("delete removes the session directory") {
        val fs = MemoryFs(
          Map(
            "/home/sessions/%2Frepo"                 -> Dir,
            "/home/sessions/%2Frepo/s1"              -> Dir,
            "/home/sessions/%2Frepo/s1/summary.json" -> File(1, """{"info":{"id":"s1","cwd":"/repo"}}"""),
            "/home/sessions/%2Frepo/s2"              -> Dir,
            "/home/sessions/%2Frepo/s2/summary.json" -> File(
              2,
              """{"info":{"id":"s2","cwd":"/repo"},"generated_title":"Keep"}""",
            ),
          )
        )
        for
          gone  <- SessionEdit.delete(fs, "/home", "/repo", "s1")
          rows  <- SessionIndex.listRows(fs, "/home", "/repo")
          isDir <- fs.isDirectory("/home/sessions/%2Frepo/s1")
        yield assertTrue(gone, rows.map(_.id) == List("s2"), !isDir)
      },
    )

  sealed trait Entry
  case object Dir                                  extends Entry
  final case class File(mtime: Long, text: String) extends Entry

  final class MemoryFs(initial: Map[String, Entry]) extends SessionFs:
    private var files                                           = initial
    def listNames(dir: String): BeardError.Result[List[String]] =
      val prefix = if dir.endsWith("/") then dir else dir + "/"
      ZIO.succeed(
        files.keys.iterator
          .filter(p => p.startsWith(prefix) && !p.substring(prefix.length).contains("/"))
          .map(_.substring(prefix.length))
          .toList
      )
    def isDirectory(path: String): BeardError.Result[Boolean] =
      ZIO.succeed(files.get(path).contains(Dir))
    def mtimeMs(path: String): BeardError.Result[Option[Long]] =
      ZIO.succeed(files.get(path).collect { case File(m, _) => m })
    def readText(path: String): BeardError.Result[Option[String]] =
      ZIO.succeed(files.get(path).collect { case File(_, t) => t })
    override def writeText(path: String, text: String): BeardError.Result[Unit] =
      ZIO.succeed {
        val now = files.get(path) match
          case Some(File(m, _)) => m + 1
          case _                => 1L
        files += path -> File(now, text)
      }
    override def deleteTree(path: String): BeardError.Result[Unit] =
      ZIO.succeed {
        val prefix = if path.endsWith("/") then path else path + "/"
        files = files.filterNot((p, _) => p == path || p.startsWith(prefix))
      }
  end MemoryFs
end SessionEditSpec
