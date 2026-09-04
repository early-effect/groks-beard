package groksbeard.core

import zio.json.*
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

final case class JsonSchema(
    @jsonField("type") schemaType: Option[String] = None,
    properties: Option[Map[String, JsonSchema]] = None,
    required: Option[List[String]] = None,
    additionalProperties: Option[Boolean] = None,
    description: Option[String] = None,
    minimum: Option[Int] = None,
    minItems: Option[Int] = None,
    items: Option[JsonSchema] = None,
    @jsonField("enum") enumValues: Option[List[String]] = None,
) derives JsonCodec

final case class McpToolSpec(
    name: String,
    description: String,
    inputSchema: JsonSchema,
) derives JsonCodec

final case class McpToolAnnotations(readOnlyHint: Boolean, destructiveHint: Boolean) derives JsonCodec

final case class McpListedTool(
    name: String,
    description: String,
    inputSchema: JsonSchema,
    annotations: McpToolAnnotations,
) derives JsonCodec

final case class WorkspaceRootResult(root: String) derives JsonCodec
final case class OpenFilesArgs(cursor: Option[String] = None) derives JsonCodec
final case class OpenFilesResult(
    tabs: List[String] = Nil,
    active: Option[String] = None,
    truncated: Boolean = false,
    nextCursor: Option[String] = None,
) derives JsonCodec

@jsonNoExtraFields
final case class PathLineArgs(path: String, line: Option[Int] = None) derives JsonCodec

final case class OkResult(ok: Boolean = true, reason: Option[String] = None) derives JsonCodec

enum FileKind:
  case add, modify, delete, move

object FileKind:
  given JsonCodec[FileKind] = JsonCodec(
    JsonEncoder[String].contramap(_.toString),
    JsonDecoder[String].mapOrFail { s =>
      FileKind.values.find(_.toString == s).toRight(s"unknown kind: $s")
    },
  )

@jsonNoExtraFields
final case class ShowChangesFile(path: String, kind: FileKind) derives JsonCodec

@jsonNoExtraFields
final case class ShowChangesArgs(title: Option[String] = None, files: List[ShowChangesFile]) derives JsonCodec

final case class ShowChangesResult(ok: Boolean, shown: Int) derives JsonCodec

final case class SelectionResult(
    truncated: Boolean,
    path: Option[String] = None,
    absPath: Option[String] = None,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None,
    startCol: Option[Int] = None,
    endCol: Option[Int] = None,
    text: Option[String] = None,
    languageId: Option[String] = None,
    atRef: Option[String] = None,
) derives JsonCodec

trait McpToolHost:
  def workspaceRoot(): WorkspaceRootResult
  def selection(): SelectionResult
  def openFiles(cursor: Option[String]): OpenFilesResult
  def reveal(path: String, line: Option[Int]): OkResult
  def openDiff(path: String, line: Option[Int]): OkResult
  def showChanges(title: Option[String], files: List[ShowChangesFile]): ShowChangesResult

object McpTools:
  val Annotations: McpToolAnnotations =
    McpToolAnnotations(readOnlyHint = true, destructiveHint = false)

  private val emptyObject: JsonSchema =
    JsonSchema(schemaType = Some("object"), properties = Some(Map.empty))

  private val pathProp: JsonSchema =
    JsonSchema(schemaType = Some("string"), description = Some("Workspace-relative or absolute path."))

  private val lineProp: JsonSchema =
    JsonSchema(schemaType = Some("integer"), minimum = Some(1), description = Some("1-based line to reveal."))

  private val pathLine: JsonSchema =
    JsonSchema(
      schemaType = Some("object"),
      properties = Some(Map("path" -> pathProp, "line" -> lineProp)),
      required = Some(List("path")),
    )

  private val pathLineStrict: JsonSchema =
    pathLine.copy(additionalProperties = Some(false))

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
      JsonSchema(
        schemaType = Some("object"),
        properties = Some(
          Map(
            "cursor" -> JsonSchema(
              schemaType = Some("string"),
              description = Some("Opaque cursor from a previous nextCursor."),
            )
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
      JsonSchema(
        schemaType = Some("object"),
        additionalProperties = Some(false),
        properties = Some(
          Map(
            "title" -> JsonSchema(schemaType = Some("string")),
            "files" -> JsonSchema(
              schemaType = Some("array"),
              minItems = Some(1),
              items = Some(
                JsonSchema(
                  schemaType = Some("object"),
                  additionalProperties = Some(false),
                  properties = Some(
                    Map(
                      "path" -> JsonSchema(schemaType = Some("string")),
                      "kind" -> JsonSchema(
                        schemaType = Some("string"),
                        enumValues = Some(List("add", "modify", "delete", "move")),
                      ),
                    )
                  ),
                  required = Some(List("path", "kind")),
                )
              ),
            ),
          )
        ),
        required = Some(List("files")),
      ),
    ),
  )

  def listed: List[McpListedTool] =
    Specs.map(s => McpListedTool(s.name, s.description, s.inputSchema, Annotations))

  def dispatch(name: String, args: Json, host: McpToolHost): Either[String, Json] =
    McpTool.fromName(name) match
      case None       => Left(s"Unknown tool: $name")
      case Some(tool) =>
        tool match
          case McpTool.WorkspaceRoot => Right(host.workspaceRoot().asJson)
          case McpTool.Selection     => Right(host.selection().asJson)
          case McpTool.OpenFiles     =>
            val cursor = args.as[OpenFilesArgs].toOption.flatMap(_.cursor)
            Right(host.openFiles(cursor).asJson)
          case McpTool.Reveal =>
            args.as[PathLineArgs] match
              case Left(_)        => Left(s"${McpTool.wire(tool)}: invalid arguments")
              case Right(decoded) => Right(host.reveal(decoded.path, decoded.line).asJson)
          case McpTool.OpenDiff =>
            args.as[PathLineArgs] match
              case Left(_)        => Left(s"${McpTool.wire(tool)}: invalid arguments")
              case Right(decoded) => Right(host.openDiff(decoded.path, decoded.line).asJson)
          case McpTool.ShowChanges =>
            args.as[ShowChangesArgs] match
              case Right(decoded) if decoded.files.nonEmpty =>
                Right(host.showChanges(decoded.title, decoded.files).asJson)
              case _ => Left(s"${McpTool.wire(tool)}: invalid arguments")

  def selectionResult(
      path: Option[String],
      absPath: Option[String],
      startLine: Option[Int],
      endLine: Option[Int],
      text: Option[String],
      languageId: Option[String],
  ): SelectionResult =
    val truncated = text.exists(t => Utf8.byteLength(t) > McpTool.SelectionCapBytes)
    val clipped   = text.map(Utf8.truncateToByteCap(_, McpTool.SelectionCapBytes))
    val atRef     =
      (path, startLine, endLine) match
        case (Some(p), Some(s), Some(e)) => Some(s"@$p:$s-$e")
        case _                           => None
    SelectionResult(
      truncated = truncated,
      path = path,
      absPath = absPath,
      startLine = startLine,
      endLine = endLine,
      text = clipped,
      languageId = languageId,
      atRef = atRef,
    )
  end selectionResult

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
end McpTools
