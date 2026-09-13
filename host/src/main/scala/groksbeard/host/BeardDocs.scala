package groksbeard.host

import groksbeard.host.vscode.*

import scala.scalajs.js

final class BeardDocs extends TextDocumentContentProvider:
  private val bodies  = scala.collection.mutable.Map.empty[String, String]
  private val emitter = new EventEmitter[Uri]()
  val onDidChange: js.Function1[js.Function1[js.UndefOr[Uri], Any], Disposable] = emitter.event

  def provideTextDocumentContent(uri: Uri, token: CancellationToken): String =
    bodies.getOrElse(BeardDocs.key(uri.scheme, uri.path), "")

  def setPair(path: String, original: String, proposed: String): Unit =
    val norm = BeardDocs.normalize(path)
    bodies.update(BeardDocs.key(BeardDocs.Original, norm), original)
    bodies.update(BeardDocs.key(BeardDocs.Proposed, norm), proposed)
    emitter.fire(originalUri(path))
    emitter.fire(proposedUri(path))

  def originalUri(path: String): Uri =
    BeardDocs.uri(BeardDocs.Original, path)

  def proposedUri(path: String): Uri =
    BeardDocs.uri(BeardDocs.Proposed, path)
end BeardDocs

object BeardDocs:
  val Original = "beard-original"
  val Proposed = "beard-proposed"

  def normalize(path: String): String =
    val posix = path.replace('\\', '/')
    if posix.startsWith("/") then posix else s"/$posix"

  def uri(scheme: String, path: String): Uri =
    vscode.Uri.parse(s"$scheme:${groksbeard.core.WorkspacePlan.encodeUriPath(normalize(path))}")

  def key(scheme: String, path: String): String =
    s"$scheme:${normalize(path)}"
end BeardDocs
