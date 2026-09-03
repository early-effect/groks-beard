package groksbeard.core

import zio.json.ast.Json

enum McpTool:
  case WorkspaceRoot, Selection, OpenFiles, Reveal, OpenDiff, ShowChanges

object McpTool:
  val SelectionCapBytes: Int = 20_000

  val Names: List[String] = List(
    "editor_workspace_root",
    "editor_selection",
    "editor_open_files",
    "editor_reveal",
    "editor_open_diff",
    "editor_show_changes",
  )

  def fromName(name: String): Option[McpTool] =
    name match
      case "editor_workspace_root" => Some(McpTool.WorkspaceRoot)
      case "editor_selection"      => Some(McpTool.Selection)
      case "editor_open_files"     => Some(McpTool.OpenFiles)
      case "editor_reveal"         => Some(McpTool.Reveal)
      case "editor_open_diff"      => Some(McpTool.OpenDiff)
      case "editor_show_changes"   => Some(McpTool.ShowChanges)
      case _                       => None

  def wire(tool: McpTool): String =
    tool match
      case McpTool.WorkspaceRoot => "editor_workspace_root"
      case McpTool.Selection     => "editor_selection"
      case McpTool.OpenFiles     => "editor_open_files"
      case McpTool.Reveal        => "editor_reveal"
      case McpTool.OpenDiff      => "editor_open_diff"
      case McpTool.ShowChanges   => "editor_show_changes"
end McpTool

final case class McpToolSpec(
    name: String,
    description: String,
    inputSchema: Json,
)

trait McpToolHost:
  def workspaceRoot(): Json
  def selection(): Json
  def openFiles(cursor: Option[String]): Json
  def reveal(path: String, line: Option[Int]): Json
  def openDiff(path: String, line: Option[Int]): Json
  def showChanges(title: Option[String], files: List[(String, String)]): Json

object McpTools:
  val Annotations: Json =
    Json.Obj("readOnlyHint" -> Json.Bool(true), "destructiveHint" -> Json.Bool(false))

  private val emptyObject: Json =
    Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj())

  private val pathLine: Json =
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "path" -> Json
          .Obj("type" -> Json.Str("string"), "description" -> Json.Str("Workspace-relative or absolute path.")),
        "line" -> Json.Obj(
          "type"        -> Json.Str("integer"),
          "minimum"     -> Json.Num(1),
          "description" -> Json.Str("1-based line to reveal."),
        ),
      ),
      "required" -> Json.Arr(Json.Str("path")),
    )

  private val pathLineStrict: Json =
    Json.Obj(
      "type"                 -> Json.Str("object"),
      "additionalProperties" -> Json.Bool(false),
      "properties"           -> Json.Obj(
        "path" -> Json
          .Obj("type" -> Json.Str("string"), "description" -> Json.Str("Workspace-relative or absolute path.")),
        "line" -> Json.Obj(
          "type"        -> Json.Str("integer"),
          "minimum"     -> Json.Num(1),
          "description" -> Json.Str("1-based line to reveal."),
        ),
      ),
      "required" -> Json.Arr(Json.Str("path")),
    )

  val Specs: List[McpToolSpec] = List(
    McpToolSpec(
      "editor_workspace_root",
      "Return the workspace folder open in VS Code or Cursor with Grok's Beard.",
      emptyObject,
    ),
    McpToolSpec(
      "editor_selection",
      "Return the current editor selection or the pending Copy Selection / Add Selection buffer. Prefers an @path:start-end atRef. Text is truncated.",
      emptyObject,
    ),
    McpToolSpec(
      "editor_open_files",
      "List open editor tab paths and the active file. Paths only, no file bodies. Pass cursor from nextCursor to page; results are capped and set truncated when more remain.",
      Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "cursor" -> Json.Obj(
            "type"        -> Json.Str("string"),
            "description" -> Json.Str("Opaque cursor from a previous nextCursor."),
          )
        ),
      ),
    ),
    McpToolSpec(
      "editor_reveal",
      "Reveal a file in the editor, optionally at a 1-based line. Does not write files.",
      pathLine,
    ),
    McpToolSpec(
      "editor_open_diff",
      "Open a native diff for a path using a Beard snapshot, git HEAD, or disk. Paths only. Never send oldText or newText.",
      pathLineStrict,
    ),
    McpToolSpec(
      "editor_show_changes",
      "Show a path-only Grok Changes navigation tree. Does not write files, invent snapshots, or git-snapshot.",
      Json.Obj(
        "type"                 -> Json.Str("object"),
        "additionalProperties" -> Json.Bool(false),
        "properties"           -> Json.Obj(
          "title" -> Json.Obj("type" -> Json.Str("string")),
          "files" -> Json.Obj(
            "type"     -> Json.Str("array"),
            "minItems" -> Json.Num(1),
            "items"    -> Json.Obj(
              "type"                 -> Json.Str("object"),
              "additionalProperties" -> Json.Bool(false),
              "properties"           -> Json.Obj(
                "path" -> Json.Obj("type" -> Json.Str("string")),
                "kind" -> Json.Obj(
                  "type" -> Json.Str("string"),
                  "enum" -> Json.Arr(Json.Str("add"), Json.Str("modify"), Json.Str("delete"), Json.Str("move")),
                ),
              ),
              "required" -> Json.Arr(Json.Str("path"), Json.Str("kind")),
            ),
          ),
        ),
        "required" -> Json.Arr(Json.Str("files")),
      ),
    ),
  )

  def dispatch(name: String, args: Json, host: McpToolHost): Either[String, Json] =
    McpTool.fromName(name) match
      case None       => Left(s"Unknown tool: $name")
      case Some(tool) =>
        val obj = args match
          case o: Json.Obj => o
          case _           => Json.Obj()
        tool match
          case McpTool.WorkspaceRoot => Right(host.workspaceRoot())
          case McpTool.Selection     => Right(host.selection())
          case McpTool.OpenFiles     =>
            val cursor = obj.get("cursor") match
              case Some(Json.Str(s)) => Some(s)
              case _                 => None
            Right(host.openFiles(cursor))
          case McpTool.Reveal =>
            stringField(obj, "path") match
              case None       => Left(s"${McpTool.wire(tool)}: invalid arguments")
              case Some(path) => Right(host.reveal(path, positiveInt(obj, "line")))
          case McpTool.OpenDiff =>
            if hasExtra(obj, Set("path", "line")) then Left(s"${McpTool.wire(tool)}: invalid arguments")
            else
              stringField(obj, "path") match
                case None       => Left(s"${McpTool.wire(tool)}: invalid arguments")
                case Some(path) => Right(host.openDiff(path, positiveInt(obj, "line")))
          case McpTool.ShowChanges =>
            decodeShowChanges(obj) match
              case None              => Left(s"${McpTool.wire(tool)}: invalid arguments")
              case Some((title, fs)) => Right(host.showChanges(title, fs))
        end match

  def selectionJson(
      path: Option[String],
      absPath: Option[String],
      startLine: Option[Int],
      endLine: Option[Int],
      text: Option[String],
      languageId: Option[String],
  ): Json =
    val truncated = text.exists(t => Utf8.byteLength(t) > McpTool.SelectionCapBytes)
    val clipped   = text.map(Utf8.truncateToByteCap(_, McpTool.SelectionCapBytes))
    val atRef     =
      (path, startLine, endLine) match
        case (Some(p), Some(s), Some(e)) => Some(s"@$p:$s-$e")
        case _                           => None
    val fields =
      List("truncated" -> Json.Bool(truncated)) ++
        path.toList.map(p => "path" -> Json.Str(p)) ++
        absPath.toList.map(p => "absPath" -> Json.Str(p)) ++
        startLine.toList.map(n => "startLine" -> Json.Num(n)) ++
        endLine.toList.map(n => "endLine" -> Json.Num(n)) ++
        clipped.toList.map(t => "text" -> Json.Str(t)) ++
        languageId.toList.map(l => "languageId" -> Json.Str(l)) ++
        atRef.toList.map(a => "atRef" -> Json.Str(a))
    Json.Obj(fields*)
  end selectionJson

  def sidecarFile(path: String, kind: String): FileChange =
    FileChange(
      path = path,
      kind = ChangeKind.fromWire(kind),
      additions = 0,
      deletions = 0,
      wholeFile = false,
      toolCallId = "sidecar",
      undoDisabled = Some("Undo needs an editor chat snapshot."),
    )

  private def stringField(obj: Json.Obj, key: String): Option[String] =
    obj.get(key) match
      case Some(Json.Str(s)) if s.nonEmpty => Some(s)
      case _                               => None

  private def positiveInt(obj: Json.Obj, key: String): Option[Int] =
    obj.get(key) match
      case Some(Json.Num(n)) if n.intValue > 0 => Some(n.intValue)
      case _                                   => None

  private def hasExtra(obj: Json.Obj, allowed: Set[String]): Boolean =
    val Json.Obj(fields) = obj
    fields.exists((k, _) => !allowed.contains(k))

  private def decodeShowChanges(obj: Json.Obj): Option[(Option[String], List[(String, String)])] =
    if hasExtra(obj, Set("title", "files")) then None
    else
      val title = stringField(obj, "title")
      obj.get("files") match
        case Some(Json.Arr(items)) if items.nonEmpty =>
          val files = items.toList.flatMap {
            case o: Json.Obj =>
              if hasExtra(o, Set("path", "kind")) then None
              else
                (stringField(o, "path"), stringField(o, "kind")) match
                  case (Some(p), Some(k)) if Set("add", "modify", "delete", "move").contains(k) =>
                    Some((p, k))
                  case _ => None
            case _ => None
          }
          if files.size == items.size then Some((title, files)) else None
        case _ => None
      end match
end McpTools
