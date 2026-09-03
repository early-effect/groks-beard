package groksbeard.host

import groksbeard.core.{ChangeSet, FileChange}
import groksbeard.host.vscode.*

import scala.scalajs.js

final class ChangesTree(files: () => List[FileChange]) extends TreeDataProvider[String]:
  private val emitter                                                                      = new EventEmitter[String]()
  val onDidChangeTreeData: js.Function1[js.Function1[js.UndefOr[String], Any], Disposable] =
    emitter.event

  def refresh(): Unit = emitter.fire()

  def getTreeItem(element: String): TreeItem =
    files().find(_.path == element) match
      case None       => new TreeItem(element, TreeItemCollapsible.None)
      case Some(file) =>
        val item = new TreeItem(fileName(file.path), TreeItemCollapsible.None)
        item.id = element
        item.description = s"${ChangeSet.formatStats(file.additions, file.deletions)}"
        item.tooltip = file.path
        item.contextValue = if file.undoDisabled.isDefined then "changeFileNoUndo" else "changeFile"
        item.command = js.Dynamic.literal(
          command = "groksBeard.openDiff",
          title = "Open Diff",
          arguments = js.Array(file.path),
        )
        item

  def getChildren(element: js.UndefOr[String]): js.Array[String] =
    if element.isDefined then js.Array()
    else js.Array(files().map(_.path)*)

  private def fileName(path: String): String =
    val norm = path.replace('\\', '/')
    val i    = norm.lastIndexOf('/')
    if i < 0 then path else norm.substring(i + 1)
end ChangesTree
