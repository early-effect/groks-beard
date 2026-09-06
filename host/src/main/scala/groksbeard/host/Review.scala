package groksbeard.host

import groksbeard.core.{BeardError, DiffPair, FollowAlong, UndoMutation}
import groksbeard.host.vscode.*
import zio.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

final class Review(docs: BeardDocs):
  def follow(path: String, line: Option[Int]): Unit =
    val uri                          = vscode.Uri.file(abs(path))
    val scheme                       = vscode.window.activeTextEditor.toOption.map(_.document.uri.scheme)
    val selection: js.UndefOr[Range] = line.filter(_ > 0) match
      case Some(n) => new VsCodeRange(n - 1, 0, n - 1, 0)
      case None    => js.undefined
    val column: js.UndefOr[Int] = FollowAlong.viewColumn(scheme) match
      case Some(n) => n
      case None    => js.undefined
    val opts = new TextDocumentShowOptions(
      preserveFocus = true,
      preview = true,
      viewColumn = column,
      selection = selection,
    )
    val _ = vscode.window.showTextDocument(uri, opts).`catch`((_: Any) => ())
    ()
  end follow

  def open(title: String, pairs: List[DiffPair]): Unit =
    if pairs.isEmpty then ()
    else
      val uris = js.Array(pairs.map { p =>
        docs.setPair(p.path, p.oldText, p.newText)
        js.Array(docs.originalUri(p.path), docs.proposedUri(p.path))
      }*)
      val opened                            = vscode.commands.executeCommand[js.Any]("vscode.changes", title, uris)
      val fallback: js.Function1[Any, Unit] = (_: Any) =>
        pairs.foreach { p =>
          val orig = docs.originalUri(p.path)
          val prop = docs.proposedUri(p.path)
          val _    = vscode.commands.executeCommand[js.Any]("vscode.diff", orig, prop, p.path)
        }
      val _ = opened.`catch`(fallback)
      ()

  def readDisk(path: String): Option[String] = None

  def applyUndo(mutations: List[UndoMutation]): BeardError.Result[Unit] =
    ZIO.foreachDiscard(mutations) {
      case UndoMutation.Replace(path, text) => write(path, text)
      case UndoMutation.Create(path, text)  => write(path, text)
      case UndoMutation.Delete(path)        =>
        ChangeDisk.fromPromise(vscode.workspace.fs.delete(vscode.Uri.file(path)))
    }

  private def write(path: String, text: String): BeardError.Result[Unit] =
    ChangeDisk.fromPromise(vscode.workspace.fs.writeFile(vscode.Uri.file(path), Review.utf8(text)))

  private def abs(path: String): String =
    if path.startsWith("/") || path.matches("^[a-zA-Z]:[\\\\/].*") then path
    else
      vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath) match
        case Some(root) => s"$root/$path"
        case None       => path
end Review

object Review:
  def utf8(text: String): Uint8Array =
    js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(text).asInstanceOf[Uint8Array]
