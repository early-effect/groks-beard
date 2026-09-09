package groksbeard.core

final case class ThemeDef(
    id: String,
    name: String,
    description: String,
    vars: Map[String, String],
)

object Theme:
  val Default: String = "vscode"
  val Auto: String    = "auto"

  val All: List[ThemeDef] = List(
    ThemeDef(
      "vscode",
      "VS Code",
      "Inherit the editor theme (terminal analogue).",
      Map.empty,
    ),
    ThemeDef(
      Auto,
      "Auto",
      "Follow the editor theme (same as VS Code).",
      Map.empty,
    ),
    ThemeDef(
      "groknight",
      "Grok Night",
      "Neutral dark with a magenta accent.",
      Map(
        "--vscode-foreground"            -> "#e8d5e8",
        "--vscode-descriptionForeground" -> "#9a8898",
        "--vscode-sideBar-background"    -> "#1a1218",
        "--vscode-input-background"      -> "#2a1c26",
        "--vscode-input-foreground"      -> "#e8d5e8",
        "--vscode-widget-border"         -> "#4a2e40",
        "--vscode-menu-background"       -> "#2a1c26",
      ),
    ),
    ThemeDef(
      "grokday",
      "Grok Day",
      "Light theme for bright backgrounds.",
      Map(
        "--vscode-foreground"            -> "#2a1a24",
        "--vscode-descriptionForeground" -> "#6b5464",
        "--vscode-sideBar-background"    -> "#f7f0f4",
        "--vscode-input-background"      -> "#ffffff",
        "--vscode-input-foreground"      -> "#2a1a24",
        "--vscode-widget-border"         -> "#d4c4ce",
        "--vscode-menu-background"       -> "#fff8fb",
      ),
    ),
    ThemeDef(
      "tokyonight",
      "Tokyo Night",
      "Dark, blue-tinted backgrounds.",
      Map(
        "--vscode-foreground"            -> "#c0caf5",
        "--vscode-descriptionForeground" -> "#565f89",
        "--vscode-sideBar-background"    -> "#1a1b26",
        "--vscode-input-background"      -> "#24283b",
        "--vscode-input-foreground"      -> "#c0caf5",
        "--vscode-widget-border"         -> "#3b4261",
        "--vscode-menu-background"       -> "#1f2335",
      ),
    ),
    ThemeDef(
      "rosepine",
      "Rosé Pine Moon",
      "Muted dark with mauve accents.",
      Map(
        "--vscode-foreground"            -> "#e0def4",
        "--vscode-descriptionForeground" -> "#908caa",
        "--vscode-sideBar-background"    -> "#232136",
        "--vscode-input-background"      -> "#2a273f",
        "--vscode-input-foreground"      -> "#e0def4",
        "--vscode-widget-border"         -> "#393552",
        "--vscode-menu-background"       -> "#2a273f",
      ),
    ),
    ThemeDef(
      "oscura",
      "Oscura Midnight",
      "Deep dark with purple accents.",
      Map(
        "--vscode-foreground"            -> "#e6e1f0",
        "--vscode-descriptionForeground" -> "#8b82a0",
        "--vscode-sideBar-background"    -> "#0e0c14",
        "--vscode-input-background"      -> "#1a1624",
        "--vscode-input-foreground"      -> "#e6e1f0",
        "--vscode-widget-border"         -> "#3a3150",
        "--vscode-menu-background"       -> "#1a1624",
      ),
    ),
  )

  def pick(query: String, ids: List[ThemeDef] = All): Option[ThemeDef] =
    val q = canonicalize(query)
    if q.isEmpty then None
    else
      ids
        .find(_.id == q)
        .orElse(ids.find(_.name.equalsIgnoreCase(query.trim)))
        .orElse(ids.find(_.id.startsWith(q)))

  def canonicalize(raw: String): String =
    val n = raw.trim.toLowerCase.replace('_', '-').replace(' ', '-')
    n match
      case "grok-night" | "dark" | "night"                                                  => "groknight"
      case "grok-day" | "light" | "day"                                                     => "grokday"
      case "tokyo-night" | "tokyo"                                                          => "tokyonight"
      case "rose-pine" | "rosepine-moon" | "rose-pine-moon" | "rose"                        => "rosepine"
      case "oscura-midnight"                                                                => "oscura"
      case "terminal" | "terminal-default" | "transparent" | "native" | "vscode" | "editor" =>
        "vscode"
      case "system" | "auto" => Auto
      case other             => other
    end match
  end canonicalize

  def cycle(current: String): ThemeDef =
    val ids = All
    val i   = ids.indexWhere(_.id == canonicalize(current))
    ids((i + 1) % ids.length)

  def css(theme: ThemeDef): String =
    if theme.vars.isEmpty then ""
    else
      val body = theme.vars.map { (k, v) => s"$k: $v;" }.mkString(" ")
      s"[data-theme='${theme.id}'] { $body }"

  def allCss: String =
    All.map(css).filter(_.nonEmpty).mkString("\n")
end Theme
