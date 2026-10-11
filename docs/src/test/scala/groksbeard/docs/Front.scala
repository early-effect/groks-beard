package groksbeard.docs

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import mermoid.{Diagram, LayoutConfig, Mermaid, RenderConfig, SequenceConfig, SequenceModel, SvgNode, SvgRenderer}
import specular.*
import specular.site.ProjectMeta
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Marketplace id and the VSIX install from this repo. No Maven line, and no version token. */
object Install:
  val marketplaceId: String = "early-effect.groks-beard"

  val marketplaceUrl: String =
    "https://marketplace.visualstudio.com/items?itemName=early-effect.groks-beard"

  val openVsxUrl: String =
    "https://open-vsx.org/extension/early-effect/groks-beard"

  val commands: String =
    """sbt --no-server host/packageVsix
      |code --install-extension groks-beard.vsix --force
      |# Cursor: cursor --install-extension groks-beard.vsix --force""".stripMargin

  /** Summary-index snippet. [[BuildSite]] still copies the front page over `index.html`. */
  val blurb: String = s"$marketplaceId\n\n$commands"
end Install

/** The site front. Specular still emits a summary index; [[BuildSite]] copies this page over it.
  *
  * Not a sidebar item. Not a cite: Grok's Beard is the extension, not a published Scala library.
  */
object Front extends DocSpecSuite:

  /** Phone content is about 360px after the theme's side padding. Three short columns stay inside that. */
  private val figure =
    RenderConfig(
      layout = LayoutConfig(
        charWidthEstimate = 7.2,
        padding = 6,
        fontSize = 13,
        edgeLabelFontSize = 12,
        lineHeight = 16,
        arrowSize = 8,
      ),
      sequence = SequenceConfig(
        actorMinWidth = 48,
        actorPadH = 6,
        actorHeight = 28,
        columnGap = 8,
        rowPitch = 36,
        headerGap = 8,
        footerGap = 8,
      ),
    )

  /** Editor calls `activate`. The extension spawns `grok` on stdio. Chat `Send` is `session/prompt`. */
  private val exchange =
    Mermaid("""sequenceDiagram
              |    participant Editor
              |    participant Extension
              |    participant Grok
              |    Editor->>Extension: activate
              |    Extension->>Grok: agent<br/>stdio
              |    Editor->>Extension: Send
              |    Extension->>Grok: session/<br/>prompt
              |    Grok-->>Extension: session/<br/>update
              |    Extension-->>Editor: AgentChunk
              |""".stripMargin)

  private val painted: SvgNode = SvgRenderer.renderTree(exchange.diagram, figure)

  private val sceneWidth: Option[Double] = viewBoxWidth(painted)

  private val diagram: UI[Any] =
    sceneWidth match
      case Some(px) => SvgEmbed.root(painted, px)
      case None     => SvgEmbed.root(painted, 0)

  def doc = page("The editor asks. Grok answers on stdio.")(
    md"""
Grok's Beard is a VS Code and Cursor client for [Grok Build](https://x.ai). The extension spawns the `grok` CLI. It never holds your key.
""",
    illustration(diagram).assert { ui =>
      val text  = plainText(ui)
      val box   = attr(ui, "svg", "viewBox")
      val width = box.flatMap(viewBoxWidthOf)
      val style = attr(ui, "svg", "style")
      assertTrue(
        text.contains("Editor"),
        text.contains("Extension"),
        text.contains("Grok"),
        text.contains("activate"),
        text.contains("Send"),
        text.contains("AgentChunk"),
        text.contains("stdio"),
        text.contains("prompt"),
        text.contains("update"),
        attr(ui, "svg", "width").contains("100%"),
        style.exists(_.contains("height:auto")),
        style.exists(_.contains("max-width:")),
        style.exists(_.contains("aspect-ratio:")),
        width.exists(_ > 0),
        width.exists(_ <= 360),
      ).label(s"viewBox $box")
    },
    md"""
`activate` is the extension entry. `agent` and `stdio` are how it starts `grok`. `Send` is the chat view. That becomes `session/prompt`. `session/update` comes back as `AgentChunk` in the editor.
""",
    section("Install")(
      md"""
The Marketplace id is `${Install.marketplaceId}`. The [Marketplace](${Install.marketplaceUrl}) and [Open VSX](${Install.openVsxUrl}) listings are still an older TypeScript build. Install this build from the repo:

```bash
${Install.commands}
```

You need VS Code 1.105 or newer, or Cursor, and the [Grok Build CLI](https://x.ai/cli) (`grok`) on your PATH. Sign in with `grok login` or `XAI_API_KEY`. The extension never holds the key.

Reload the window. Open chat with `Ctrl+;` or `Cmd+;`.
"""
    ),
    section("Names")(
      md"""
Not a fork of the community Grok Build extension. Not affiliated with or endorsed by SpaceXAI (formerly xAI). *Grok* and *Grok Build* are trademarks of xAI; this project uses those names only to describe what it is compatible with.

Created by [Russell White](https://github.com/russwyte). Published as `${Install.marketplaceId}`.
"""
    ),
  )

  override def spec =
    suite("The editor asks. Grok answers on stdio.")(
      super.spec,
      test("the sequence is editor, extension, then Grok") {
        val order = participants
        val px    = sceneWidth
        assertTrue(
          order == List("Editor", "Extension", "Grok"),
          exchange.source.contains("sequenceDiagram"),
          exchange.source.contains("activate"),
          exchange.source.contains("agent<br/>stdio"),
          exchange.source.contains("Send"),
          exchange.source.contains("session/<br/>prompt"),
          exchange.source.contains("session/<br/>update"),
          exchange.source.contains("AgentChunk"),
          figure.layout.fontSize >= 12,
          figure.layout.charWidthEstimate >= 7.0,
          px.exists(_ > 0),
          px.exists(_ <= 360),
        ).label(s"scene width $px")
      },
      test("install is the marketplace id and the VSIX, not a Maven coordinate") {
        val text = pageText(doc)
        assertTrue(
          text.contains(Install.marketplaceId),
          text.contains(Install.marketplaceUrl),
          text.contains(Install.openVsxUrl),
          text.contains("sbt --no-server host/packageVsix"),
          text.contains("code --install-extension groks-beard.vsix --force"),
          text.contains("cursor --install-extension groks-beard.vsix --force"),
          text.contains("trademarks of xAI"),
          text.contains("never holds your key"),
          !text.contains("libraryDependencies"),
          !text.contains("addSbtPlugin"),
          !text.contains("%%"),
          !text.contains("SNAPSHOT"),
          !text.contains("-ci"),
          !text.contains("0.2.4"),
          !text.contains("0.1.0"),
          !text.contains("0.0.0"),
          !text.contains("<version>"),
          Install.blurb.contains(Install.marketplaceId),
          !Install.blurb.contains("libraryDependencies"),
        )
      },
      test("chrome drops a dynver distance and a stripped zero") {
        val samples = List("0.0.0-ci", "0.0.0", "0.2.4", "0.2.4-1-gabcdef")
        val badges  = samples.map(raw => Chrome.quiet(sample(raw)).versionBadge)
        val shown   = samples.map(raw => Chrome.quiet(sample(raw)).docsVersion)
        assertTrue(
          badges.forall(_.isEmpty),
          shown.forall(_.isEmpty),
          Chrome.quiet(sample("0.0.0-ci").copy(displayVersion = Some("0.0.0"))).versionBadge.isEmpty,
        )
      },
    )

  private def participants: List[String] =
    exchange.diagram match
      case Diagram.Sequence(stmts) =>
        SequenceModel.participantOrder(stmts).map(_.value)
      case Diagram.Flowchart(_, _) | Diagram.StateDiagram(_, _) | Diagram.ClassDiagram(_, _) |
          Diagram.ErDiagram(_, _) =>
        Nil

  private def sample(version: String): ProjectMeta =
    ProjectMeta(
      name = "groks-beard-root",
      organization = "rocks.earlyeffect",
      version = version,
      scalaVersion = "3.9.0",
    )

  private def pageText(page: DocPage): String =
    def walk(nodes: Vector[DocNode]): String =
      nodes
        .map {
          case Prose(markdown)          => markdown
          case Section(title, children) => s"$title\n${walk(children)}"
          case _: Example               => ""
          case _: Illustration          => ""
          case _: ValueExample[?, ?]    => ""
          case _: FailExample           => ""
          case _: CrashExample[?, ?]    => ""
          case _: DomExample            => ""
          case _: SourceCite            => ""
        }
        .mkString("\n")
    s"${page.title}\n${walk(page.children)}"
  end pageText

  private def plainText(ui: UI[Any]): String = ui match
    case UI.Text(value)            => value
    case element: UI.Element[Any]  => element.children.map(plainText).mkString
    case UI.Empty                  => ""
    case UI.Fragment(children)     => children.map(plainText).mkString
    case UI.ReactiveText(_)        => ""
    case UI.ReactiveChild(_)       => ""
    case UI.When(_, _)             => ""
    case UI.ForEach(_, _, _)       => ""
    case UI.ForEachSignal(_, _, _) => ""
    case UI.Scoped(_)              => ""
    case UI.ServerRegion(_, _)     => ""

  private def attr(ui: UI[Any], tag: String, name: String): Option[String] = ui match
    case element: UI.Element[Any] if element.tag == tag =>
      element.attrs.collectFirst { case Attr.StaticAttr(`name`, AttrValue.Str(raw)) => raw }
    case element: UI.Element[Any] =>
      element.children.iterator.map(node => attr(node, tag, name)).collectFirst { case Some(raw) => raw }
    case UI.Fragment(children) =>
      children.iterator.map(node => attr(node, tag, name)).collectFirst { case Some(raw) => raw }
    case UI.Text(_) | UI.Empty | UI.ReactiveText(_) | UI.ReactiveChild(_) | UI.When(_, _) | UI.ForEach(_, _, _) |
        UI.ForEachSignal(_, _, _) | UI.Scoped(_) | UI.ServerRegion(_, _) =>
      None

  private def viewBoxWidth(node: SvgNode): Option[Double] = node match
    case SvgNode.Element("svg", attrs, _) =>
      attrs.collectFirst { case ("viewBox", raw) => raw }.flatMap(viewBoxWidthOf)
    case SvgNode.Element(_, _, children) =>
      children.iterator.map(viewBoxWidth).collectFirst { case Some(width) => width }
    case SvgNode.Text(_) | SvgNode.Raw(_) =>
      None

  /** Third number of `viewBox="0 0 W H"`. */
  private def viewBoxWidthOf(raw: String): Option[Double] =
    raw.split("\\s+").toList.filter(_.nonEmpty) match
      case _ :: _ :: width :: _ => width.toDoubleOption
      case _                    => None
end Front

/** Root SVG scaled to the content column, capped at the painted width so a wide desktop does not blow it up. */
private object SvgEmbed:

  def root(node: SvgNode, maxWidth: Double): UI[Any] = node match
    case SvgNode.Element("svg", attrs, children) =>
      element("svg", fit(attrs, maxWidth), children)
    case other =>
      paint(other)

  private def paint(node: SvgNode): UI[Any] = node match
    case SvgNode.Text(value) =>
      UI.Text(value)
    case SvgNode.Raw(content) =>
      UI.Text(content)
    case SvgNode.Element(tag, attrs, children) =>
      element(tag, attrs, children)

  private def element(tag: String, attrs: List[(String, String)], children: List[SvgNode]): UI[Any] =
    UI.Element(
      tag,
      attrs.map((name, value) => Attr.StaticAttr(name, AttrValue.Str(value))).toVector,
      children.map(paint).toVector,
    )

  /** Drop the painted pixel box. `viewBox` keeps the drawing. `aspect-ratio` keeps the height, because `height:auto` on
    * an inline SVG collapses in Firefox.
    */
  private def fit(attrs: List[(String, String)], maxWidth: Double): List[(String, String)] =
    val kept   = attrs.filter((name, _) => name != "width" && name != "height" && name != "style")
    val cap    = if maxWidth > 0 then s"max-width:${maxWidth}px;" else ""
    val aspect = kept.collectFirst { case ("viewBox", raw) => raw }.flatMap(aspectRatio).getOrElse("")
    kept ++ List(
      "width" -> "100%",
      "style" -> s"height:auto;${cap}${aspect}display:block",
    )

  private def aspectRatio(raw: String): Option[String] =
    raw.split("\\s+").toList.filter(_.nonEmpty) match
      case _ :: _ :: width :: height :: _ =>
        (width.toDoubleOption, height.toDoubleOption) match
          case (Some(w), Some(h)) if w > 0 && h > 0 => Some(s"aspect-ratio:$w / $h;")
          case _                                    => None
      case _ =>
        None
end SvgEmbed
