import _root_.sbt.*
import _root_.sbt.Keys.*
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import ascent.preview.sbt.AscentPreviewPort

/** Background serve for `~uiJS/ascentPreview`: LiveMain (Preview.routes ++ grok ACP), not stock PreviewMain. */
object BeardPreview:
  private val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  val liveMain: String = "groksbeard.preview.LiveMain"

  def serveLive: Def.Initialize[Task[Unit]] = Def.task {
    val service   = bgJobService.value
    val log       = streams.value.log
    val converter = fileConverter.value
    val st        = state.value
    val rs        = Keys.resolvedScoped.value
    val root      = ascentPreviewRoot.value
    val requested = ascentPreviewPort.value
    val cp        = ascentPreviewClasspath.value
    val autoOpen  = ascentPreviewAutoOpen.value
    val base      = baseDirectory.value
    val repoRoot  = (ThisBuild / baseDirectory).value
    val already   = service.jobs.exists { job =>
      job.spawningTask.key.label == ascentPreviewServe.key.label &&
      job.spawningTask.scope.project == rs.scope.project
    }
    if already then log.info(s"ascentPreviewServe: already running ${root.getAbsolutePath}")
    else
      if !root.exists then
        sys.error(s"ascentPreviewServe: root does not exist: $root (run ascentPreviewRebuild first)")
      val port = AscentPreviewPort.resolve(requested)
      IO.createDirectory(base / "target")
      IO.write(base / "target" / "ascent-preview.port", port.toString)
      val jars =
        cp.map(af => converter.toPath(af.data).toFile.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val args = Seq("-cp", jars, liveMain, port.toString, root.getAbsolutePath) ++
        (if autoOpen then Seq("--open") else Nil)
      log.info(s"ascentPreviewServe: $liveMain on http://localhost:$port/ ${root.getAbsolutePath}")
      service.runInBackground(rs, st) { (logger, _) =>
        val opts = ForkOptions()
          .withOutputStrategy(Some(LoggedOutput(logger)))
          .withRunJVMOptions(jdk24PlusRunOptions.toVector)
          .withWorkingDirectory(repoRoot)
        val code = Fork.java(opts, args)
        if code != 0 then sys.error(s"LiveMain exited $code")
      }
      ()
    end if
  }
end BeardPreview
