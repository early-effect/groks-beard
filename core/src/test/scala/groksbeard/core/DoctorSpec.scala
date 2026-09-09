package groksbeard.core

import zio.test.*

object DoctorSpec extends ZIOSpecDefault:
  def spec =
    suite("Doctor")(
      test("tty probes are not applicable") {
        val rows = Doctor.collect(None, None, true, false, true, true, true, "/repo", 0, "ready", None)
        assertTrue(
          rows.exists(f => f.id == "cli" && !f.ok),
          rows.exists(f => f.id == "tmux" && f.na),
          Doctor.headline(rows).contains("finding"),
        )
      },
      test("fixes lists only failed findings with a repair") {
        val rows = Doctor.collect(None, None, true, false, true, true, true, "/repo", 0, "ready", None)
        assertTrue(
          Doctor.fixes(rows).exists(_.id == "cli"),
          !Doctor.fixes(rows).exists(_.id == "tmux"),
          !Doctor.fixes(rows).exists(_.ok),
        )
      },
    )
end DoctorSpec
