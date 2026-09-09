package groksbeard.core

import zio.test.*

object SettingKeySpec extends ZIOSpecDefault:
  def spec =
    suite("SettingKey")(
      test("flag keys are the boolean vscode settings") {
        val off = SettingKey.ShareBackend.patch(SettingsState.defaults, false)
        assertTrue(
          SettingKey.ShareBackend.flag,
          SettingKey.UseCtrlEnterToSend.flag,
          SettingKey.IncludeActiveFile.flag,
          !SettingKey.CliPath.flag,
          !SettingKey.NodePath.flag,
          !SettingKey.ChangesPresentation.flag,
          SettingKey.parse("shareBackend").contains(SettingKey.ShareBackend),
          SettingKey.parse("nope").isEmpty,
          !off.shareBackend,
          SettingKey.ShareBackend.toggle(SettingsState.defaults).contains(false),
          SettingKey.Panel ==
            List(SettingKey.ShareBackend, SettingKey.UseCtrlEnterToSend, SettingKey.IncludeActiveFile),
        )
      }
    )
end SettingKeySpec
