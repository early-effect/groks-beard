package groksbeard.core

import zio.test.*

object PromptBlockSpec extends ZIOSpecDefault:
  def spec =
    suite("PromptBlock")(
      test("embedded chips become resource blocks") {
        val chip = PromptChip.fromSelection(
          "/repo/src/Foo.scala",
          Some("/repo"),
          Some(1),
          Some(2),
          excerpt = Some("object Foo"),
        )
        val blocks = PromptBlock.of("explain", List(chip), embedded = true)
        assertTrue(
          blocks.exists:
            case PromptBlock.Text(t) => t.contains("@src/Foo.scala")
            case _                   => false
          ,
          blocks.exists:
            case PromptBlock.Resource(res) => res.text.contains("object Foo") && res.uri.startsWith("file://")
            case _                         => false
          ,
          blocks.lastOption.contains(PromptBlock.Text("explain")),
        )
      },
      test("image chips become image blocks when advertised") {
        val img = ImageChip("image-0", "image/png", "AAAA", "paste.png")
        val on  = ImageAttach.blocks(List(img), image = true, embedded = false)
        val off = ImageAttach.blocks(List(img), image = false, embedded = true)
        assertTrue(
          on.exists:
            case PromptBlock.Image(data, mime) => data == "AAAA" && mime == "image/png"
            case _                             => false
          ,
          off.exists:
            case PromptBlock.Resource(res) => res.blob.contains("AAAA")
            case _                         => false,
        )
      },
    )
end PromptBlockSpec
