package groksbeard.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.DocPage
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}

/** No published tag. Header and footer show no version.
  *
  * The docs workflow sets `SPECULAR_STRIP_CI`, which ignores `specularDisplayVersion` and would turn `0.0.0-ci` into
  * `0.0.0`. An empty display version wins: the badge is absent, not a dynver distance and not a made-up release.
  */
object Chrome:
  def quiet(meta: ProjectMeta): ProjectMeta =
    meta.copy(displayVersion = Some(""))

/** Specular DocsSite: Test classpath main invoked by `docs/specularSite`. */
object BuildSite extends DocsSite:

  /** Not a nav item. This site is one page. */
  private val frontPage: DocPage = Front.doc

  def pages: Vector[DocPage] = Vector(frontPage)

  override def site(settings: DocsSettings): SiteModel =
    val meta =
      Chrome
        .quiet(settings.meta)
        .copy(
          name = "groks-beard",
          title = Some("Grok's Beard"),
          description = Some("A VS Code and Cursor client for Grok Build."),
        )
    EarlyEffectTheme
      .brand(
        super
          .site(settings)
          .copy(
            title = "Grok's Beard",
            meta = Some(meta),
            description = meta.description,
          )
      )
      .copy(
        nav = Some(NavModel.flat(Vector.empty)),
        summaryMarkdown = None,
        installSnippets = Vector(CodeSnippet("Install", Install.blurb)),
      )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): IO[SiteError, Unit] =
    val _ = result
    // Specular still writes index.html as a summary plus a second copy of the nav.
    // The front DocPage is the index. Copy it over that file. Asset paths stay site-relative.
    val front   = out.resolve(s"${frontPage.slug}.html")
    val index   = out.resolve("index.html")
    val promote =
      ZIO
        .attemptBlockingIO(Files.copy(front, index, StandardCopyOption.REPLACE_EXISTING))
        .mapError { err =>
          SiteError.WriteFailed(index, err)
        }
    promote *> EarlyEffectTheme.writeLogo(out)
  end afterBuild
end BuildSite
