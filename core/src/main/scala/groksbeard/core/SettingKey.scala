package groksbeard.core

import ascent.squawk.Eq
import zio.json.*

/** Beard settings keys. Wire strings match `groksBeard.*` in package.json. */
enum SettingKey(val wire: String, val flag: Boolean):
  case CliPath             extends SettingKey("cliPath", false)
  case NodePath            extends SettingKey("nodePath", false)
  case IncludeActiveFile   extends SettingKey("includeActiveFileByDefault", true)
  case UseCtrlEnterToSend  extends SettingKey("useCtrlEnterToSend", true)
  case ChangesPresentation extends SettingKey("changesPresentation", false)
  case ShareBackend        extends SettingKey("shareBackend", true)

  def patch(state: SettingsState, value: String | Boolean): SettingsState =
    this match
      case SettingKey.UseCtrlEnterToSend =>
        value match
          case b: Boolean => state.copy(useCtrlEnterToSend = b)
          case _          => state
      case SettingKey.ShareBackend =>
        value match
          case b: Boolean => state.copy(shareBackend = b)
          case _          => state
      case SettingKey.IncludeActiveFile =>
        value match
          case b: Boolean => state.copy(includeActiveFileByDefault = b)
          case _          => state
      case SettingKey.ChangesPresentation =>
        value match
          case s: String => state.copy(changesPresentation = s)
          case _         => state
      case SettingKey.CliPath =>
        value match
          case s: String => state.copy(cliPath = s)
          case _         => state
      case SettingKey.NodePath =>
        value match
          case s: String => state.copy(nodePath = s)
          case _         => state

  def toggle(state: SettingsState): Option[Boolean] =
    this match
      case SettingKey.ShareBackend       => Some(!state.shareBackend)
      case SettingKey.UseCtrlEnterToSend => Some(!state.useCtrlEnterToSend)
      case SettingKey.IncludeActiveFile  => Some(!state.includeActiveFileByDefault)
      case _                             => None

  def panelLabel(state: SettingsState): String =
    this match
      case SettingKey.ShareBackend =>
        if state.shareBackend then "Share Grok: on" else "Share Grok: off"
      case SettingKey.UseCtrlEnterToSend =>
        if state.useCtrlEnterToSend then "Ctrl+Enter to send: on" else "Ctrl+Enter to send: off"
      case SettingKey.IncludeActiveFile =>
        if state.includeActiveFileByDefault then "Include active file: on"
        else "Include active file: off"
      case _ => wire

  def testId: String =
    this match
      case SettingKey.ShareBackend        => "share-backend"
      case SettingKey.UseCtrlEnterToSend  => "ctrl-enter"
      case SettingKey.IncludeActiveFile   => "active-file"
      case SettingKey.CliPath             => "cli-path"
      case SettingKey.NodePath            => "node-path"
      case SettingKey.ChangesPresentation => "changes-presentation"

  def hint: String =
    this match
      case SettingKey.ShareBackend =>
        "Join a running Grok leader so the TUI stays in sync. The TUI must enable [cli] use_leader itself. Beard does not write that setting."
      case _ => ""
end SettingKey

object SettingKey:
  val Panel: List[SettingKey] =
    List(SettingKey.ShareBackend, SettingKey.UseCtrlEnterToSend, SettingKey.IncludeActiveFile)

  def parse(raw: String): Option[SettingKey] =
    SettingKey.values.find(_.wire == raw)

  given JsonCodec[SettingKey] =
    JsonExt.stringCodecOrFail(_.wire, raw => parse(raw).toRight(s"unknown setting: $raw"))

  given Eq[SettingKey] = Eq.derived
end SettingKey
