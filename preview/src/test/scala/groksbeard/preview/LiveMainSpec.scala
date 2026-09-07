package groksbeard.preview

import java.nio.file.Path as JPath
import zio.*
import zio.test.*

object LiveMainSpec extends ZIOSpecDefault:
  def spec =
    suite("LiveMain")(
      test("configFromArgs reads port, root, and --open in PreviewMain order") {
        val site = JPath.of("/tmp/site").toAbsolutePath.normalize
        val a    = LiveMain.configFromArgs(Chunk("9000", "/tmp/site", "--open"))
        val b    = LiveMain.configFromArgs(Chunk("--open", "9000", "/tmp/site"))
        val c    = LiveMain.configFromArgs(Chunk.empty)
        assertTrue(
          a.port == 9000,
          a.openBrowser,
          a.root == site,
          b.port == 9000,
          b.openBrowser,
          b.root == site,
          c.port == 8765,
          !c.openBrowser,
          c.root.endsWith(JPath.of("ui", "target", "preview")),
        )
      },
      test("logoFile walks up to media/logo.png") {
        val tmp     = java.nio.file.Files.createTempDirectory("beard-logo")
        val media   = tmp.resolve("media")
        val preview = tmp.resolve("ui").resolve("target").resolve("preview")
        java.nio.file.Files.createDirectories(media)
        java.nio.file.Files.createDirectories(preview)
        java.nio.file.Files.write(media.resolve("logo.png"), Array[Byte](1, 2, 3))
        val found = LiveMain.logoFile(preview)
        assertTrue(found.contains(media.resolve("logo.png").toFile))
      },
    )
end LiveMainSpec
