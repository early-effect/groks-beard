import ascent.preview.sbt.AscentPreviewPlugin
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import chekhov.sbt.ChekhovPlugin.autoImport.*
import sbt.nio.Keys.watchOnTermination
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import rocks.earlyeffect.splice.SplicePlugin.autoImport.*

MyVersions.settings

lazy val beardVersion =
  settingKey[String]("Marketplace / VSIX version written into package.json")

lazy val stampVsixVersion =
  taskKey[File]("Write beardVersion into package.json")

ThisBuild / beardVersion := "0.2.1"
ThisBuild / scalaVersion := (MyVersions.scala: String)

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

val scalaVersions = Seq[String](MyVersions.scala)

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
    stampVsixVersion := Def.uncached {
      val base = (ThisBuild / baseDirectory).value
      BeardPack.stampPackageJson(base, (ThisBuild / beardVersion).value)
      base / "package.json"
    },
  )

// JVM + JS. `core/testFull` is JVM-only; CI links and runs coreJS. Use `testCore`.
lazy val core = (projectMatrix in file("core"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "groks-beard-core",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    MyVersions.zioLib,
    MyVersions.jsonLib,
    MyVersions.ascentCore,
    zioTestSettings,
    Compile / sourceGenerators += Def.task {
      val out = BeardPack.writeProductVersion(
        (Compile / sourceManaged).value,
        (ThisBuild / beardVersion).value,
      )
      Seq(out)
    }.taskValue,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)

lazy val facade = (project in file("facade"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "groks-beard-facade",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    javaTimePolyfill,
  )

lazy val preview = (project in file("preview"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(LocalProject("core"))
  .settings(
    name := "groks-beard-preview",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    MyVersions.previewServer,
    zioTestSettings,
    Compile / mainClass       := Def.uncached(Some("groksbeard.preview.LiveMain")),
    Compile / run / mainClass := Def.uncached(Some("groksbeard.preview.LiveMain")),
    Compile / run / fork      := true,
    Compile / run / javaOptions ++= Seq(
      "--sun-misc-unsafe-memory-access=allow",
      "--enable-native-access=ALL-UNNAMED",
    ),
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
  )

lazy val ui = (projectMatrix in file("ui"))
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
          chekhovInstall := Def.uncached {
            val log      = streams.value.log
            val browsers = chekhovBrowsers.value.toList
            val names    = chekhov.protocol.PinnedPlaywright.installPackageNames(browsers)
            def ok(cli: java.nio.file.Path): Unit =
              log.info(
                s"Pinned Playwright ${chekhov.protocol.PinnedPlaywright.version} CLI: $cli (${names.mkString(", ")})"
              )
            chekhov.protocol.PinnedPlaywright.install(
              browsers = browsers,
              log      = msg => log.info(msg),
            ) match
              case Right(cli) => ok(cli)
              case Left(err)  =>
                log.warn(s"chekhovInstall with install-deps failed: $err; installing browsers only")
                val cli  = chekhov.protocol.PinnedPlaywright.cliInCache()
                val node = sys.env.getOrElse("PLAYWRIGHT_NODEJS_PATH", "node")
                val cmd  = Seq(node, cli.toString, "install") ++ names
                val code = scala.sys.process.Process(cmd).!
                if code != 0 then sys.error(s"chekhov: ${cmd.mkString(" ")} exited $code")
                else ok(cli)
          },
          Test / fork    := false,
          Test / jsEnv   := Def.uncached(chekhovJSEnv.value),
          Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
          Compile / scalaJSUseMainModuleInitializer := true,
          Test / scalaJSUseMainModuleInitializer    := false,
          spliceFastOutput                := Def.uncached(ascentPreviewRoot.value / "fast.js"),
          spliceFullOutput                := Def.uncached(
            (ThisBuild / baseDirectory).value / "ui" / "target" / "splice" / "full.js"
          ),
          ascentPreviewAutoServe := true,
          ascentPreviewMain      := "groksbeard.preview.LiveMain",
          ascentPreviewClasspath := Def.uncached((LocalProject("preview") / Compile / fullClasspath).value),
          ascentPreviewServe     := Def.uncached(BeardPreview.serveLive.value),
          ascentPreview / watchOnTermination := BeardPreview.watchStop,
          ascentPreviewRebuild               := Def.uncached {
            val dest = ascentPreviewStage.value
            IO.copyFile((ThisBuild / baseDirectory).value / "media" / "logo.png", dest / "logo.png")
            ()
          },
        ),
  )

lazy val stageExtension =
  taskKey[File]("Copy host/mcp fastLinkJS and ui spliceFull into dist")

lazy val packageVsix =
  taskKey[File]("fullLinkJS host and mcp, spliceFull ui, then pack groks-beard.vsix")

lazy val host = (project in file("host"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(LocalProject("coreJS"), facade)
  .settings(
    name := "groks-beard-host",
    skipPublish,
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
      val dest = (ThisBuild / baseDirectory).value / "dist"
      BeardPack.stampPackageJson((ThisBuild / baseDirectory).value, (ThisBuild / beardVersion).value)
      val hostOut = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val _       = (Compile / fastLinkJS).value
      val chat    = (LocalProject("uiJS") / spliceFull).value
      val mcpOut  = (LocalProject("mcp") / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val _mcp    = (LocalProject("mcp") / Compile / fastLinkJS).value
      BeardPack.stageDist(dest, hostOut / "main.js", chat, mcpOut / "main.js")
    },
    packageVsix := Def.uncached {
      val dest = (ThisBuild / baseDirectory).value / "dist"
      val base = (ThisBuild / baseDirectory).value
      BeardPack.stampPackageJson(base, (ThisBuild / beardVersion).value)
      val hostOut = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val _       = (Compile / fullLinkJS).value
      val chat    = (LocalProject("uiJS") / spliceFull).value
      val mcpOut  = (LocalProject("mcp") / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val _mcp    = (LocalProject("mcp") / Compile / fullLinkJS).value
      BeardPack.stageDist(dest, hostOut / "main.js", chat, mcpOut / "main.js")
      val vsix = base / "groks-beard.vsix"
      import scala.sys.process.*
      val code = Process(
        Seq("npx", "--yes", "@vscode/vsce", "package", "--no-dependencies", "-o", vsix.getAbsolutePath),
        base,
      ).!
      if code != 0 then sys.error(s"vsce package failed with $code")
      vsix
    },
  )

lazy val mcp = (project in file("mcp"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(LocalProject("coreJS"))
  .settings(
    name := "groks-beard-mcp",
    skipPublish,
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
