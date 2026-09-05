import _root_.sbt.*
import _root_.sbt.Keys.*
import _root_.sbt.nio.Watch
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import ascent.preview.sbt.AscentPreviewPort

/** `~uiJS/ascentPreview` starts LiveMain through `preview/bgRun`.
  *
  * Do not assemble `fullClasspath` into `java -cp`. sbt 2 exports that classpath as CAS jars;
  * a module in this build is started with `run` / `bgRun`.
  */
object BeardPreview:
  val liveMain: String  = "groksbeard.preview.LiveMain"
  val PreviewId: String = "preview"

  val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  def isLiveJob(job: JobHandle): Boolean =
    val key   = job.spawningTask
    val label = key.key.label
    val inPreview = key.scope.project match
      case Select(ProjectRef(_, id)) => id == PreviewId
      case Select(LocalProject(id))  => id == PreviewId
      case _                         => false
    (inPreview && (label == "bgRun" || label == "bgRunMain")) ||
    label == ascentPreviewServe.key.label

  def stopLive(service: BackgroundJobService): Unit =
    service.jobs.filter(isLiveJob).foreach { h =>
      service.stop(h)
      service.waitForTry(h)
      ()
    }

  def watchStop: (Watch.Action, String, Int, State) => State =
    (_, _, _, state) =>
      stopLive(Project.extract(state).get(bgJobService))
      state

  def serveLive: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val service   = bgJobService.value
    val log       = streams.value.log
    val root      = ascentPreviewRoot.value
    val requested = ascentPreviewPort.value
    val autoOpen  = ascentPreviewAutoOpen.value
    val base      = baseDirectory.value
    if service.jobs.exists(isLiveJob) then
      Def.task {
        log.info(s"ascentPreviewServe: already running ${root.getAbsolutePath}")
        ()
      }
    else if !root.exists then
      sys.error(s"ascentPreviewServe: root does not exist: $root (run ascentPreviewRebuild first)")
    else
      val port = AscentPreviewPort.resolve(requested)
      IO.createDirectory(base / "target")
      IO.write(base / "target" / "ascent-preview.port", port.toString)
      val extra =
        s" $port ${root.getAbsolutePath}" + (if autoOpen then " --open" else "")
      log.info(s"ascentPreviewServe: preview/bgRun ($liveMain)$extra")
      (LocalProject(PreviewId) / Compile / bgRun).toTask(extra).map(_ => ())
  }
end BeardPreview
