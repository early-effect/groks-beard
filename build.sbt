import ascent.preview.sbt.AscentPreviewPlugin
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import chekhov.sbt.ChekhovPlugin.autoImport.*
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import rocks.earlyeffect.splice.SplicePlugin.autoImport.*

MyVersions.settings

val scala3Version: String = MyVersions.scala

organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
versionScheme        := Some("early-semver")

homepage := Some(url("https://github.com/early-effect/groks-beard"))
licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo  := Some(
  ScmInfo(
    url("https://github.com/early-effect/groks-beard"),
    "scm:git@github.com:early-effect/groks-beard.git",
  )
)
developers := List(
  Developer(
    "russwyte",
    "Russ White",
    "356303+russwyte@users.noreply.github.com",
    url("https://github.com/russwyte"),
  )
)

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if isSnapshot.value then Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle    := true
pomIncludeRepository := { _ => false }

usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

zipxJavaVersion := JdkVersion("25")
zipxEnv += "PLAYWRIGHT_BROWSERS_PATH" ->
  EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright"))
zipxCapabilities += Capability
  .once(
    name = Capability.TestName,
    command = zipxTasks.session(
      LocalProject("uiJS") / chekhovInstall,
      testFull,
      LocalProject("uiJS") / spliceFull,
    ),
  )
  .withNodeVersion(NodeVersion("24"))

addCommandAlias("testCore", "core/testFull; coreJS/testFull")
addCommandAlias("verifyBeard", "uiJS/chekhovInstall; testFull; uiJS/spliceFull")

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

val scalaVersions = Seq(scala3Version)

val zioTestSettings = MyVersions.zioTests

val skipPublish = Seq(
  publish / skip    := true,
  publishArtifact   := false,
)

val javaTimePolyfill = MyVersions.javaTime

lazy val root = (project in file("."))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .aggregate(
    LocalProject("core"),
    LocalProject("coreJS"),
    LocalProject("uiJS"),
    facade,
    preview,
    host,
    mcp,
  )
  .settings(
    name := "groks-beard-root",
    skipPublish,
    test / skip := true,
  )

// JVM + JS. `core/testFull` is JVM-only; CI links and runs coreJS. Use `testCore`.
lazy val core = (projectMatrix in file("beard/core"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "groks-beard-core",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    MyVersions.zioLib,
    MyVersions.jsonLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)

lazy val facade = (project in file("beard/facade"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "groks-beard-facade",
    skipPublish,
    scalaVersion := scala3Version,
    scalacOptions ++= commonScalacOptions,
    javaTimePolyfill,
  )

lazy val preview = (project in file("beard/preview"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "groks-beard-preview",
    skipPublish,
    scalaVersion := scala3Version,
    scalacOptions ++= commonScalacOptions,
    MyVersions.previewServer,
  )

lazy val ui = (projectMatrix in file("beard/ui"))
  .dependsOn(core)
  .settings(
    name := "groks-beard-ui",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    MyVersions.ascentUi,
    zioTestSettings,
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.enablePlugins(AscentPreviewPlugin)
        .dependsOn(facade)
        .settings(
          javaTimePolyfill,
          MyVersions.chekhovUi,
          chekhovBrowser := "firefox",
          Test / fork    := false,
          Test / jsEnv   := Def.uncached(chekhovJSEnv.value),
          Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
          Compile / scalaJSUseMainModuleInitializer := true,
          Test / scalaJSUseMainModuleInitializer    := false,
          spliceFastOutput                := Def.uncached(ascentPreviewRoot.value / "fast.js"),
          spliceFullOutput                := Def.uncached(
            (ThisBuild / baseDirectory).value / "beard" / "ui" / "target" / "splice" / "full.js"
          ),
          ascentPreviewAutoServe          := true,
          ascentPreviewClasspath          := Def.uncached((LocalProject("preview") / Compile / fullClasspath).value),
          ascentPreviewRebuild            := Def.uncached {
            val dest = ascentPreviewStage.value
            val logo = (ThisBuild / baseDirectory).value / "beard" / "media" / "logo.png"
            IO.copyFile(logo, dest / "logo.png")
            ()
          },
        ),
  )

lazy val stageExtension =
  taskKey[File]("Copy host fastLinkJS, mcp-proxy, and ui spliceFull into beard/dist")

lazy val packageVsix =
  taskKey[File]("Stage the extension and pack beard/groks-beard.vsix with vsce")

lazy val host = (project in file("beard/host"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(LocalProject("coreJS"))
  .settings(
    name := "groks-beard-host",
    skipPublish,
    scalaVersion := scala3Version,
    scalacOptions ++= commonScalacOptions,
    javaTimePolyfill,
    MyVersions.zioLib,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / skip     := true,
    Test / sources  := Nil,
    Test / test     := Def.uncached(sbt.protocol.testing.TestResult.Passed),
    Test / testFull := Def.uncached(sbt.protocol.testing.TestResult.Passed),
    stageExtension := Def.uncached {
      val dest    = (ThisBuild / baseDirectory).value / "beard" / "dist"
      val webview = dest / "webview"
      IO.createDirectory(webview)
      val hostOut = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val _       = (Compile / fastLinkJS).value
      IO.copyFile(hostOut / "main.js", dest / "extension.js")
      val chat = (LocalProject("uiJS") / spliceFull).value
      IO.copyFile(chat, webview / "chat.js")
      val mcpOut = (LocalProject("mcp") / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val _mcp   = (LocalProject("mcp") / Compile / fastLinkJS).value
      IO.copyFile(mcpOut / "main.js", dest / "mcp-proxy.js")
      dest
    },
    packageVsix := Def.uncached {
      val dest = stageExtension.value
      val base = (ThisBuild / baseDirectory).value
      val cwd  = base / "beard"
      val vsix = cwd / "groks-beard.vsix"
      IO.copyFile(base / "LICENSE", cwd / "LICENSE")
      import scala.sys.process.*
      val code = Process(
        Seq("npx", "--yes", "@vscode/vsce", "package", "--no-dependencies", "-o", vsix.getAbsolutePath),
        cwd,
      ).!
      if code != 0 then sys.error(s"vsce package failed with $code")
      val _ = dest
      vsix
    },
  )

lazy val mcp = (project in file("beard/mcp"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(LocalProject("coreJS"))
  .settings(
    name := "groks-beard-mcp",
    skipPublish,
    scalaVersion := scala3Version,
    scalacOptions ++= commonScalacOptions,
    javaTimePolyfill,
    MyVersions.zioLib,
    MyVersions.jsonLib,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / skip     := true,
    Test / sources  := Nil,
    Test / test     := Def.uncached(sbt.protocol.testing.TestResult.Passed),
    Test / testFull := Def.uncached(sbt.protocol.testing.TestResult.Passed),
  )
