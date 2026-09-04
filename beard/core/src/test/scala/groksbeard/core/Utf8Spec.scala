package groksbeard.core

import zio.test.*
import zio.test.Gen

object Utf8Spec extends ZIOSpecDefault:
  def spec =
    suite("Utf8")(
      test("truncateToByteCap never exceeds the cap and is a prefix") {
        val text = Gen.stringBounded(0, 64)(Gen.char.filterNot(_.isSurrogate))
        check(text, Gen.int(0, 180)) { (s, cap) =>
          val t = Utf8.truncateToByteCap(s, cap)
          assertTrue(Utf8.byteLength(t) <= cap, s.startsWith(t))
        }
      },
      test("truncateToByteCap is identity when the text already fits") {
        val text = Gen.stringBounded(0, 40)(Gen.asciiChar)
        check(text, Gen.int(0, 80)) { (s, extra) =>
          val cap = Utf8.byteLength(s) + extra
          assertTrue(Utf8.truncateToByteCap(s, cap) == s)
        }
      },
    )
end Utf8Spec
