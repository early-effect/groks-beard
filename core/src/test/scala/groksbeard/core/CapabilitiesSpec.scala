package groksbeard.core

import zio.test.*

object CapabilitiesSpec extends ZIOSpecDefault:
  def spec =
    suite("ClientCapabilities")(
      test("withholds fs.readTextFile for live-verified Grok >= 1.0.4") {
        val v    = GrokVersion.parse("grok 1.0.13").get
        val caps = ClientCapabilities.forSpawn(Some(v), verified = true, terminalHandlersReady = false)
        assertTrue(caps.fs.isEmpty, caps.terminal.isEmpty, caps.session.isDefined)
      },
      test("advertises fs.readTextFile for 1.0.3") {
        val v    = GrokVersion.parse("grok 1.0.3").get
        val caps = ClientCapabilities.forSpawn(Some(v), verified = true, terminalHandlersReady = false)
        assertTrue(caps.fs.exists(_.readTextFile))
      },
      test("advertises fs.readTextFile when the version is unverified") {
        val v    = GrokVersion.parse("grok 1.0.13").get
        val caps = ClientCapabilities.forSpawn(Some(v), verified = false, terminalHandlersReady = false)
        assertTrue(caps.fs.exists(_.readTextFile), caps.terminal.isEmpty)
      },
      test("omits terminal until handlers are ready") {
        val v      = GrokVersion.parse("grok 1.0.13").get
        val before = ClientCapabilities.forSpawn(Some(v), verified = true, terminalHandlersReady = false)
        val after  = ClientCapabilities.forSpawn(Some(v), verified = true, terminalHandlersReady = true)
        assertTrue(before.terminal.isEmpty, after.terminal.contains(true))
      },
    )
end CapabilitiesSpec
