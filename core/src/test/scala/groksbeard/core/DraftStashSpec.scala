package groksbeard.core

import zio.test.*

object DraftStashSpec extends ZIOSpecDefault:
  def spec =
    suite("DraftStash")(
      test("take ignores empty draft") {
        assertTrue(DraftStash.take("", Nil, restoreAfterSend = true).isEmpty)
      },
      test("take keeps text and restore flag") {
        val s = DraftStash.take("hello", Nil, restoreAfterSend = true)
        assertTrue(s.exists(d => d.text == "hello" && d.restoreAfterSend))
      },
      test("historyHead ranks stash first") {
        val stash = DraftStash.take("stashed", Nil, restoreAfterSend = false)
        assertTrue(DraftStash.historyHead(stash, List("older")).head == "stashed")
      },
      test("take keeps image chips") {
        val img = ImageChip("image-0", "image/png", "AAAA", "paste.png")
        val s   = DraftStash.take("pic", Nil, restoreAfterSend = true, List(img))
        assertTrue(s.exists(d => d.images == List(img) && d.restoreAfterSend))
      },
    )
end DraftStashSpec
