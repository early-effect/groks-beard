package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object OccupancySpec extends ZIOSpecDefault:
  def spec =
    suite("Occupancy")(
      test("formats compact token counts") {
        assertTrue(
          Occupancy.compact(80) == "80",
          Occupancy.compact(12_000) == "12k",
          Occupancy.compact(1_500) == "1.5k",
          Occupancy.compact(500_000) == "500k",
          Occupancy.compact(1_000_000) == "1M",
          Occupancy.label(80, 500) == "80 / 500 · 16%",
          Occupancy.tone(80, 100) == "warn",
          Occupancy.tone(90, 100) == "hot",
          Occupancy.tone(10, 100) == "ok",
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
