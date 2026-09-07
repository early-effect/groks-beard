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
      },
      test("hold publishes clients and clears them when the scope closes") {
        for
          holder  <- Ref.make(Option.empty[LiveClients])
          clients <- LiveClients.fake()
          up      <- ZIO.scoped(LiveMain.hold(holder, clients) *> holder.get)
          down    <- holder.get
        yield assertTrue(up.isDefined, down.isEmpty)
      },
    ) @@ TestAspect.sequential
end LiveMainSpec
