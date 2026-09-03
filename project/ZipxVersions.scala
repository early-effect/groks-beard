import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`).
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.8")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson    = Lib("dev.zio", "zio-json", "0.10.0")

  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.7.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val ascent        = Lib("rocks.earlyeffect", "ascent-core", "0.5.1")
  val ascentCss     = ascent.mod("ascent-css")
  val ascentJs      = ascent.mod("ascent-js")
  val ascentChekhov = ascent.mod("ascent-chekhov")
  val ascentPreview = Lib("rocks.earlyeffect", "ascent-preview", "0.5.1")

  val scalajs          = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt         = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi         = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")
  val sbtSplice        = Plugin("rocks.earlyeffect", "sbt-splice", "0.1.0")
  val sbtAscentPreview = Plugin("rocks.earlyeffect", "sbt-ascent-preview", "0.5.1")
  val sbtChekhov       = Plugin("rocks.earlyeffect", "sbt-chekhov", "0.0.5")

  def zioTests      = library(zioTest.test, zioTestSbt.test)
  def zioLib        = library(zio)
  def jsonLib       = library(zioJson)
  def javaTime      = library(scalaJavaTime, scalaJavaTimeTzdb)
  def ascentUi      = library(ascent, ascentCss, ascentJs, zio)
  def previewServer = library(ascentPreview)
  def chekhovUi     = library(ascentChekhov.test)
end MyVersions
