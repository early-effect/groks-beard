package groksbeard.preview

import java.nio.file.Path as JPath
import zio.*
import zio.test.*

object LiveMainSpec extends ZIOSpecDefault:
  def spec =
    suite("LiveMain")(
      test("configFromArgs reads port, root, and --open in PreviewMain order") {
        val site = JPath.of("site").toAbsolutePath.normalize
        val a    = LiveMain.configFromArgs(Chunk("9000", "site", "--open"))
        val b    = LiveMain.configFromArgs(Chunk("--open", "9000", "site"))
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
      }
    )
end LiveMainSpec
