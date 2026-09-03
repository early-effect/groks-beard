package groksbeard.host

import groksbeard.core.*
import groksbeard.host.vscode.*
import zio.json.ast.Json

import scala.scalajs.js

final class McpHost(review: Review, refreshTree: () => Unit):
  private var pending: Option[PromptChip]    = None
  private var sidecarFiles: List[FileChange] = Nil

  def sidecar: List[FileChange] = sidecarFiles

  def rememberSelection(chip: PromptChip): Unit =
    pending = Some(chip)

  def workspaceFolder: Option[String] =
    vscode.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri.fsPath)

  def workspaceRoot(): Json =
    Json.Obj("root" -> Json.Str(workspaceFolder.getOrElse("")))

  def selection(): Json =
    pending match
      case Some(chip) =>
        McpTools.selectionJson(
          Some(chip.path),
          Some(chip.absPath),
          chip.startLine,
          chip.endLine,
          chip.excerpt,
          chip.languageId,
        )
      case None =>
        McpTools.selectionJson(None, None, None, None, None, None)

  def openFiles(cursor: Option[String]): Json =
    val _ = cursor
    Json.Obj("tabs" -> Json.Arr(), "truncated" -> Json.Bool(false))

  def reveal(path: String, line: Option[Int]): Json =
    val uri = vscode.Uri.file(abs(path))
    val _   = vscode.commands.executeCommand[js.Any]("vscode.open", uri)
    line.foreach { n =>
      val _ = vscode.commands.executeCommand[js.Any]("revealLine", js.Dynamic.literal(lineNumber = n, at = "center"))
    }
    Json.Obj("ok" -> Json.Bool(true))

  def openDiff(path: String, line: Option[Int]): Json =
    val _ = line
    review.open(UnifiedDiff.fileName(path), List(DiffPair(abs(path), "", "", wholeFile = true)))
    Json.Obj("ok" -> Json.Bool(true))

  def showChanges(title: Option[String], files: List[(String, String)]): Json =
    val _ = title
    sidecarFiles = files.map { (p, kind) => McpTools.sidecarFile(abs(p), kind) }
    refreshTree()
    Json.Obj("ok" -> Json.Bool(true), "shown" -> Json.Num(files.size))

  def asToolHost: McpToolHost = new McpToolHost:
    def workspaceRoot()                                                   = McpHost.this.workspaceRoot()
    def selection()                                                       = McpHost.this.selection()
    def openFiles(cursor: Option[String])                                 = McpHost.this.openFiles(cursor)
    def reveal(path: String, line: Option[Int])                           = McpHost.this.reveal(path, line)
    def openDiff(path: String, line: Option[Int])                         = McpHost.this.openDiff(path, line)
    def showChanges(title: Option[String], files: List[(String, String)]) =
      McpHost.this.showChanges(title, files)

  private def abs(path: String): String =
    if path.startsWith("/") || path.matches("^[a-zA-Z]:[\\\\/].*") then path
    else workspaceFolder.map(root => s"$root/$path").getOrElse(path)
end McpHost
