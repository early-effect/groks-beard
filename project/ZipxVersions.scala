import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`).
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson    = Lib("dev.zio", "zio-json", "1.1.0")

  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.7.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val ascent        = Lib("rocks.earlyeffect", "ascent-core", "0.7.1")
  val ascentCss     = ascent.mod("ascent-css")
  val ascentJs      = ascent.mod("ascent-js")
  val ascentHistory = ascent.mod("ascent-history")
  val ascentChekhov = ascent.mod("ascent-chekhov")
  val ascentPreview = Lib("rocks.earlyeffect", "ascent-preview", "0.7.1")

  val scalajs          = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt         = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi         = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")
  val sbtSplice        = Plugin("rocks.earlyeffect", "sbt-splice", "0.3.2-42d1f350d30e-SNAPSHOT")
  // sbt-specular 0.20.0 depends on 0.10.0. The preview library below stays 0.7.1.
  val sbtAscentPreview = Plugin("rocks.earlyeffect", "sbt-ascent-preview", "0.10.0")
  val sbtChekhov       = Plugin("rocks.earlyeffect", "sbt-chekhov", "0.1.1")

  // Specular 0.20 was published against Ascent 0.10. The extension stays on 0.7.1, so docs
  // drops those edges and supplies the 0.10 jars itself. Do not bump the extension catalog.
  private val ascentTen = List(
    ZipxExclude.org("rocks.earlyeffect", "ascent-core_3"),
    ZipxExclude.org("rocks.earlyeffect", "ascent-css_3"),
    ZipxExclude.org("rocks.earlyeffect", "ascent-preview_3"),
  )

  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.20.0").excluding(ascentTen*)
  val specularZioTest = specular.mod("specular-zio-test").test
  val specularSite    = specular.mod("specular-site").test
  val specularTheme   = specular.mod("early-effect-docs-theme").test

  /** Docs figure only. The extension does not depend on mermoid. */
  val mermoid = Lib("rocks.earlyeffect", "mermoid", "0.2.0")

  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.20.0")

  def zioTests      = library(zioTest.test, zioTestSbt.test)
  def zioLib        = library(zio)
  def jsonLib       = library(zioJson)
  def ascentCore    = library(ascent)
  def javaTime      = library(scalaJavaTime, scalaJavaTimeTzdb)
  def ascentUi      = library(ascent, ascentCss, ascentJs, ascentHistory, zio)
  def previewServer = library(ascentPreview)
  def chekhovUi     = library(ascentChekhov.test)
  def docsTest      = library(specular.test, specularZioTest, specularSite, specularTheme, mermoid.test)
end MyVersions
