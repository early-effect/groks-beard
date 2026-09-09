package groksbeard.core

import zio.test.*

object AcpMethodSpec extends ZIOSpecDefault:
  def spec =
    suite("AcpMethod")(
      test("value is the JSON-RPC method name") {
        assertTrue(
          AcpMethod.SessionSetConfig.value == "session/set_config_option",
          AcpMethod.SessionNew.value == "session/new",
          AcpMethod.Interject.value == "x.ai/interject",
        )
      },
      test("parse accepts grok _x.ai aliases") {
        assertTrue(
          AcpMethod.parse("session/set_config_option").contains(AcpMethod.SessionSetConfig),
          AcpMethod.parse("_x.ai/session/fork").contains(AcpMethod.SessionFork),
          AcpMethod.parse("x.ai/session/fork").contains(AcpMethod.SessionFork),
          AcpMethod.parse("_x.ai/terminal/create").contains(AcpMethod.TerminalCreate),
          AcpMethod.parse("terminal/create").contains(AcpMethod.TerminalCreate),
          AcpMethod.parse("_x.ai/session/update").contains(AcpMethod.SessionUpdate),
          AcpMethod.parse("x.ai/session/update").contains(AcpMethod.SessionUpdate),
          AcpMethod.parse("session/update").contains(AcpMethod.SessionUpdate),
          AcpMethod.isSessionNotify("_x.ai/session/update"),
          AcpMethod.is("session/cancel", AcpMethod.SessionCancel),
        )
      },
    )
end AcpMethodSpec
