package groksbeard.core

import zio.json.ast.Json

object ToolView:
  val Tail: Int          = 4
  val Clip: Int          = 1600
  val LiveTailLines: Int = 3

  def splitTail[A](tools: List[A], tail: Int = Tail): (List[A], List[A]) =
    if tools.length <= tail then (Nil, tools)
    else (tools.dropRight(tail), tools.takeRight(tail))

  def rollupLabel(count: Int): String =
    if count == 1 then "1 earlier tool" else s"$count earlier tools"

  def clip(text: String, limit: Int = Clip): String =
    if text.length <= limit then text else s"${text.take(limit)}\n…"

  def inputOf(toolCall: AcpToolCall, diffs: List[AcpDiffBlock]): Option[String] =
    commandOf(toolCall.rawInput)
      .orElse(commandOf(toolCall.rawOutput))
      .orElse(diffs.headOption.map(_.path))
      .map(_.trim)
      .filter(_.nonEmpty)

  def outputOf(toolCall: AcpToolCall): Option[String] =
    val desc = descriptionOf(toolCall.rawInput)
    textsOf(toolCall.content)
      .filterNot(t => desc.exists(_.trim == t.trim))
      .orElse(if ToolStatus.isLive(toolCall.status) then None else promptOutput(toolCall.rawOutput))

  def liveTail(text: String, lines: Int = LiveTailLines): String =
    val rows = text.split("\\r?\\n", -1).toList
    val body = rows.reverse.dropWhile(_.isEmpty).reverse
    body.takeRight(math.max(1, lines)).mkString("\n")

  def watchText(tool: ToolRow): Option[String] =
    tool.output.filter(_.nonEmpty).orElse(tool.input.filter(_.nonEmpty))

  def descriptionOf(raw: Option[Json]): Option[String] =
    raw
      .flatMap {
        case obj: Json.Obj => str(obj, "description")
        case _             => None
      }
      .map(_.trim)
      .filter(_.nonEmpty)

  def commandOf(raw: Option[Json]): Option[String] =
    raw
      .flatMap {
        case obj: Json.Obj => str(obj, "command").orElse(str(obj, "cmd")).orElse(str(obj, "script"))
        case Json.Str(s)   => Some(s)
        case _             => None
      }
      .map(_.trim)
      .filter(_.nonEmpty)

  def textsOf(content: List[AcpContent]): Option[String] =
    val parts = content.flatMap(textBlocks)
    Option(parts.mkString).filter(_.nonEmpty)

  private def textBlocks(content: AcpContent): List[String] =
    content match
      case AcpContent.Text(text)   => if text.nonEmpty then List(text) else Nil
      case AcpContent.Block(inner) => textBlocks(inner)
      case _                       => Nil

  private def promptOutput(raw: Option[Json]): Option[String] =
    raw
      .flatMap {
        case obj: Json.Obj => str(obj, "output_for_prompt")
        case Json.Str(s)   => Some(s)
        case _             => None
      }
      .map(stripExitLine)
      .map(_.trim)
      .filter(_.nonEmpty)

  private def stripExitLine(text: String): String =
    val lines = text.split("\\r?\\n", -1)
    if lines.headOption.exists(_.trim.startsWith("exit:")) then lines.drop(1).mkString("\n")
    else text

  private def str(obj: Json.Obj, key: String): Option[String] =
    obj.fields.collectFirst { case (k, Json.Str(s)) if k == key => s }

  def permissionTip(name: String, kind: PermissionKind): String =
    kind match
      case PermissionKind.AllowOnce    => s"$name: allow this once"
      case PermissionKind.AllowAlways  => s"$name: allow this for the rest of the session"
      case PermissionKind.RejectOnce   => s"$name: skip this once"
      case PermissionKind.RejectAlways => s"$name: deny this for the rest of the session"
      case PermissionKind.Other        => name
end ToolView
