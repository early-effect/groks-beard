package groksbeard.host

import groksbeard.core.{ChangeSet, FileChange}
import groksbeard.host.vscode.*

import scala.scalajs.js

object ChangeTreeKey:
  def turn(id: String): String = s"t:$id"

  def file(turnId: String, path: String): String = s"f:$turnId\t$path"

  def turnId(raw: String): Option[String] =
    if raw.startsWith("t:") then Some(raw.drop(2)) else None

  def filePath(raw: String): Option[String] =
    if !raw.startsWith("f:") then None
    else
      val rest = raw.drop(2)
      val i    = rest.indexOf('\t')
      if i < 0 then None else Some(rest.drop(i + 1))
end ChangeTreeKey

final class ChangesTree(sets: () => List[ChangeSet]) extends TreeDataProvider[String]:
  private val emitter                                                                      = new EventEmitter[String]()
  val onDidChangeTreeData: js.Function1[js.Function1[js.UndefOr[String], Any], Disposable] =
    emitter.event

  def refresh(): Unit = emitter.fire()

  def getTreeItem(element: String): TreeItem =
    ChangeTreeKey.turnId(element) match
      case Some(id) =>
        sets().find(_.turnId == id) match
          case None      => new TreeItem(id, TreeItemCollapsible.None)
          case Some(set) =>
            val (add, del) = ChangeSet.lineStats(set.files)
            val item       = new TreeItem(set.title, TreeItemCollapsible.Expanded)
            item.id = element
            item.description = s"${ChangeSet.formatStats(add, del)}"
            item.tooltip = set.title
            item.contextValue = "changeTurn"
            item
      case None =>
        val path = ChangeTreeKey.filePath(element).getOrElse(element)
        files.find(_.path == path) match
          case None       => new TreeItem(fileName(path), TreeItemCollapsible.None)
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
        end match

  def getChildren(element: js.UndefOr[String]): js.Array[String] =
    val current = sets()
    if !element.isDefined then js.Array(current.map(s => ChangeTreeKey.turn(s.turnId))*)
    else
      ChangeTreeKey.turnId(element.get) match
        case Some(id) =>
          val files = current.find(_.turnId == id).map(_.files).getOrElse(Nil)
          js.Array(files.map(f => ChangeTreeKey.file(id, f.path))*)
        case None => js.Array()

  private def files: List[FileChange] = sets().flatMap(_.files)

  private def fileName(path: String): String =
    val norm = path.replace('\\', '/')
    val i    = norm.lastIndexOf('/')
    if i < 0 then path else norm.substring(i + 1)
end ChangesTree
