package groksbeard.core

import zio.json.ast.Json
import zio.test.*
import zio.test.Gen

object OccupancySpec extends ZIOSpecDefault:
  def spec =
    suite("Occupancy")(
      test("percent is always 0..100") {
        check(Gen.int, Gen.int) { (used, size) =>
          val p = Occupancy.percent(used, size)
          assertTrue(p >= 0, p <= 100)
        }
      },
      test("formats compact token counts") {
        assertTrue(
          Occupancy.compact(80) == "80",
          Occupancy.compact(12_000) == "12k",
          Occupancy.compact(1_500) == "1.5k",
          Occupancy.compact(1_000_000) == "1M",
          Occupancy.label(80, 500) == "80 / 500 · 16%",
          Occupancy.tone(80, 100) == "warn",
          Occupancy.tone(90, 100) == "hot",
        )
      },
      test("reads nested usage objects") {
        val json = Json.Obj(
          "update" -> Json.Obj("used" -> Json.Num(80), "size" -> Json.Num(500))
        )
        assertTrue(Occupancy.fromJson(json).contains(Occupancy(80, 500)))
      },
    )
end OccupancySpec
