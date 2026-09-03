package groksbeard.host

import groksbeard.core.{DiffPair, UndoMutation}
import groksbeard.host.vscode.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

final class Review(docs: BeardDocs):
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

  def applyUndo(mutations: List[UndoMutation]): Unit =
    mutations.foreach {
      case UndoMutation.Replace(path, text) => write(path, text)
      case UndoMutation.Create(path, text)  => write(path, text)
      case UndoMutation.Delete(path)        =>
        val _ = vscode.workspace.fs.delete(vscode.Uri.file(path))
    }

  private def write(path: String, text: String): Unit =
    val bytes = Review.utf8(text)
    val _     = vscode.workspace.fs.writeFile(vscode.Uri.file(path), bytes)
end Review

object Review:
  def utf8(text: String): Uint8Array =
    js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(text).asInstanceOf[Uint8Array]
