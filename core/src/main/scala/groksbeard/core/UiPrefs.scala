package groksbeard.core

import zio.*

/** User chrome persisted in `$GROK_HOME/config.toml` `[ui]`. Hydrate on session start; write only on user action. */
final case class UiChrome(
    theme: String = Theme.Default,
    compact: Boolean = false,
    vim: Boolean = false,
    keepChildren: Option[Boolean] = None,
):
  def host: HostMsg.UiPrefs = HostMsg.UiPrefs(theme, compact, vim)

  def patch(table: String, key: String, value: String): UiChrome =
    if table != "ui" then this
    else
      key match
        case "theme"                           => copy(theme = Theme.canonicalize(value))
        case "compact_mode"                    => copy(compact = UiChrome.truthy(value))
        case "vim_mode"                        => copy(vim = UiChrome.truthy(value))
        case "cancel_subagents_on_turn_cancel" =>
          copy(keepChildren = value match
            case "always_continue" => Some(true)
            case "always_stop"     => Some(false)
            case _                 => None)
        case _ => this
end UiChrome

object UiChrome:
  val empty: UiChrome = UiChrome()

  def fromToml(text: String): UiChrome =
    val theme   = ConfigToml.get(text, "ui", "theme").map(Theme.canonicalize).getOrElse(Theme.Default)
    val compact = ConfigToml.get(text, "ui", "compact_mode").exists(truthy)
    val vim     = ConfigToml.get(text, "ui", "vim_mode").exists(truthy)
    val keep    = ConfigToml.get(text, "ui", "cancel_subagents_on_turn_cancel").flatMap {
      case "always_continue" => Some(true)
      case "always_stop"     => Some(false)
      case _                 => None
    }
    UiChrome(theme, compact, vim, keep)
  end fromToml

  def truthy(value: String): Boolean =
    val t = value.trim.toLowerCase
    t == "true" || t == "1" || t == "yes"
end UiChrome

trait UiPrefs:
  def current: UIO[UiChrome]
  def hydrate: UIO[UiChrome]
  def setTheme(id: String): BeardError.Result[UiChrome]
  def toggleCompact: BeardError.Result[UiChrome]
  def setCompact(on: Boolean): BeardError.Result[UiChrome]
  def toggleVim: BeardError.Result[UiChrome]
  def patch(table: String, key: String, value: String): BeardError.Result[UiChrome]

object UiPrefs:
  def layer: ZLayer[SessionRepo, Nothing, UiPrefs] =
    ZLayer {
      for
        sessions <- ZIO.service[SessionRepo]
        cell     <- Ref.make(UiChrome.empty)
      yield Live(sessions, cell)
    }

  final class Live(sessions: SessionRepo, cell: Ref[UiChrome]) extends UiPrefs:
    def current: UIO[UiChrome] = cell.get

    def hydrate: UIO[UiChrome] =
      sessions.readConfig.orElseSucceed("").flatMap { text =>
        val next = UiChrome.fromToml(text)
        cell.set(next).as(next)
      }

    def setTheme(id: String): BeardError.Result[UiChrome] =
      val nextId = Theme.pick(id).map(_.id).getOrElse(Theme.canonicalize(id))
      update(_.copy(theme = nextId), "ui", "theme", nextId)

    def toggleCompact: BeardError.Result[UiChrome] =
      cell
        .updateAndGet(s => s.copy(compact = !s.compact))
        .flatMap(s => persist("ui", "compact_mode", s.compact.toString).as(s))

    def setCompact(on: Boolean): BeardError.Result[UiChrome] =
      update(_.copy(compact = on), "ui", "compact_mode", on.toString)

    def toggleVim: BeardError.Result[UiChrome] =
      cell
        .updateAndGet(s => s.copy(vim = !s.vim))
        .flatMap(s => persist("ui", "vim_mode", s.vim.toString).as(s))

    def patch(table: String, key: String, value: String): BeardError.Result[UiChrome] =
      cell.updateAndGet(_.patch(table, key, value)).flatMap(s => persist(table, key, value).as(s))

    private def update(
        f: UiChrome => UiChrome,
        table: String,
        key: String,
        value: String,
    ): BeardError.Result[UiChrome] =
      cell.updateAndGet(f).flatMap(s => persist(table, key, value).as(s))

    private def persist(table: String, key: String, value: String): BeardError.Result[Unit] =
      sessions.readConfig.flatMap { current =>
        sessions.writeConfig(ConfigToml.set(current, table, key, value))
      }
  end Live
end UiPrefs
