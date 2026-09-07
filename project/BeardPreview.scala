import _root_.sbt.*
import _root_.sbt.Keys.*
import _root_.sbt.nio.Watch
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import ascent.preview.sbt.AscentPreviewPort

/** `~uiJS/ascentPreview` forks LiveMain as `preview/bgRun` from the repo root.
  *
  * Grok sessions are keyed by process cwd (`user.dir`). The stock plugin forks
  * with the JS module's job directory; `preview/run` already uses
  * `ThisBuild / baseDirectory`. Match that here so ACP lists this workspace.
  *
  * LiveMain's HTTP process stays up. The grok host is a stamp-scoped sidecar, not this JVM's lifetime.
  * Restart the fork only when the preview classpath fingerprint changes (host codec, LiveMain).
  */
object BeardPreview:
  val liveMain: String  = "groksbeard.preview.LiveMain"
  val PreviewId: String = "preview"

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
    val converter = fileConverter.value
    val cp        = (LocalProject(PreviewId) / Compile / fullClasspath).value
    val finger    = classpathFinger(cp, converter)
    val fingerFile = base / "target" / "ascent-preview.host-cp"
    val prev       = if fingerFile.isFile then IO.read(fingerFile).trim else ""
    val already    = service.jobs.exists(isLiveJob)
    if already && prev == finger then
      Def.task {
        log.info(s"ascentPreviewServe: already running ${root.getAbsolutePath}")
        ()
      }
    else if !root.exists then
      sys.error(s"ascentPreviewServe: root does not exist: $root (run ascentPreviewRebuild first)")
    else
      if already then
        log.info("ascentPreviewServe: preview classpath changed, restarting LiveMain")
        stopLive(service)
      val port = AscentPreviewPort.resolve(requested)
      IO.createDirectory(base / "target")
      IO.write(base / "target" / "ascent-preview.port", port.toString)
      IO.write(fingerFile, finger)
      val extra =
        s" $port ${root.getAbsolutePath}" + (if autoOpen then " --open" else "")
      log.info(s"ascentPreviewServe: preview/bgRun ($liveMain)$extra")
      (LocalProject(PreviewId) / Compile / bgRun).toTask(extra).map(_ => ())
  }

  private def classpathFinger(cp: Classpath, converter: xsbti.FileConverter): String =
    cp.map { af =>
      val f = converter.toPath(af.data).toFile
      s"${f.getAbsolutePath}:${f.lastModified}"
    }.sorted.mkString("\n")
end BeardPreview
