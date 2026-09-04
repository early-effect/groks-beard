package groksbeard.core

import java.nio.file.Files

import zio.test.*

object SessionWalkSpec extends ZIOSpecDefault:
  def spec =
    suite("SessionWalk")(
      test("reads summary.json from a real directory") {
        val home = Files.createTempDirectory("beard-sessions")
        val cwd  = "/tmp/beard-walk"
        val dir  = java.nio.file.Path.of(SessionIndex.sessionPath(home.toString, cwd, "sess-1"))
        Files.createDirectories(dir)
        Files.writeString(
          dir.resolve("summary.json"),
          """{"info":{"id":"sess-1","cwd":"/tmp/beard-walk"},"generated_title":"Walked"}""",
        )
        Files.writeString(dir.resolve("updates.jsonl"), "{}\n")
        val rows = SessionWalk.fromDisk(home.toString, cwd)
        assertTrue(rows.map(_.id) == List("sess-1"), rows.head.title == "Walked")
      }
    )
end SessionWalkSpec
