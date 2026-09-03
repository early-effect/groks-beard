package groksbeard.ui

import groksbeard.core.*

final class PreviewBridge extends HostBridge:
  private var listener: HostMsg => Unit = _ => ()
  private var settings: SettingsState   = SettingsState.defaults
  private var modeId: String            = "normal"

  private val modes = List(
    ModeOption("normal", "Normal"),
    ModeOption("plan", "Plan"),
    ModeOption("auto", "Auto"),
    ModeOption("always-approve", "Always approve"),
  )

  private val commands = List(
    SlashCommand("compact", "Compact context"),
    SlashCommand("always-approve", "Skip permission prompts"),
    SlashCommand("init", "Initialize project memory"),
  )

  private val files = List(
    MentionFile("src/Main.scala", "/repo/src/Main.scala"),
    MentionFile("src/Foo.scala", "/repo/src/Foo.scala"),
    MentionFile("README.md", "/repo/README.md"),
  )

  def post(msg: WebviewMsg): Unit =
    msg match
      case WebviewMsg.Ready =>
        emit(HostMsg.Ready)
        emit(HostMsg.SessionMeta("preview", "Grok's Beard", modeId, modes))
        emit(HostMsg.AvailableCommands(commands))
        emit(HostMsg.Settings(settings))
      case WebviewMsg.MentionQuery(query) =>
        val q    = query.toLowerCase
        val hits =
          if query.isEmpty then files
          else files.filter(f => f.path.toLowerCase.contains(q))
        emit(HostMsg.MentionResults(query, hits))
      case WebviewMsg.SetMode(id) =>
        modeId = id
        emit(HostMsg.SessionMeta("preview", "Grok's Beard", modeId, modes))
      case WebviewMsg.CycleMode =>
        val ids  = modes.map(_.id)
        val next = ids.lift(ids.indexOf(modeId) + 1).getOrElse(ids.head)
        modeId = next
        emit(HostMsg.SessionMeta("preview", "Grok's Beard", modeId, modes))
      case WebviewMsg.OpenSettings =>
        emit(HostMsg.Settings(settings))
      case WebviewMsg.SetSetting(key, value) =>
        settings = key match
          case "useCtrlEnterToSend" =>
            value match
              case b: Boolean => settings.copy(useCtrlEnterToSend = b)
              case _          => settings
          case "includeActiveFileByDefault" =>
            value match
              case b: Boolean => settings.copy(includeActiveFileByDefault = b)
              case _          => settings
          case "changesPresentation" =>
            value match
              case s: String => settings.copy(changesPresentation = s)
              case _         => settings
          case "cliPath" =>
            value match
              case s: String => settings.copy(cliPath = s)
              case _         => settings
          case "nodePath" =>
            value match
              case s: String => settings.copy(nodePath = s)
              case _         => settings
          case _ => settings
        emit(HostMsg.Settings(settings))
      case WebviewMsg.Send(_) | WebviewMsg.SlashPick(_) | WebviewMsg.MentionPick(_, _) =>
        ()

  def onHost(f: HostMsg => Unit): Unit =
    listener = f

  private def emit(msg: HostMsg): Unit =
    listener(msg)
end PreviewBridge

object PreviewBridge:
  def sceneFromLocation: String =
    val search = ascent.dom.window.location.search
    val key    = "scene="
    val idx    = search.indexOf(key)
    if idx < 0 then "empty"
    else
      val rest = search.substring(idx + key.length)
      val amp  = rest.indexOf('&')
      val raw  = if amp < 0 then rest else rest.substring(0, amp)
      if raw.isEmpty then "empty" else raw
  end sceneFromLocation
end PreviewBridge
