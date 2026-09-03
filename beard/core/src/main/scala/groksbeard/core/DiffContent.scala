package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class AcpDiffBlock(
    path: String,
    oldText: String,
    newText: String,
    oldTextWasNull: Boolean,
)

final case class ToolLocation(path: String, line: Option[Int] = None)

final case class ToolCallDiffs(
    toolCallId: String,
    title: String,
    kind: String,
    status: String,
    diffs: List[AcpDiffBlock],
    replaceAll: Boolean,
    fromPath: Option[String] = None,
    locations: List[ToolLocation] = Nil,
)

final case class ReconstructedFileDiff(
    path: String,
    oldText: String,
    newText: String,
    firstChangedLine: Int,
    wholeFile: Boolean,
    kind: ChangeKind,
    toolCallId: String,
    fromPath: Option[String] = None,
    regionStandIn: Boolean = false,
)

object DiffContent:
  def diffsFromContent(content: Json): List[AcpDiffBlock] =
    content match
      case Json.Arr(items) =>
        items.toList.flatMap {
          case obj: Json.Obj =>
            obj.get("type") match
              case Some(Json.Str("diff")) =>
                obj.get("path") match
                  case Some(Json.Str(path)) =>
                    val oldRaw         = obj.get("oldText")
                    val oldTextWasNull = oldRaw.isEmpty || oldRaw.contains(Json.Null)
                    val oldText        = oldRaw.collect { case Json.Str(s) => s }.getOrElse("")
                    val newText        = obj.get("newText").collect { case Json.Str(s) => s }.getOrElse("")
                    Some(AcpDiffBlock(path, oldText, newText, oldTextWasNull))
                  case _ => None
              case _ => None
          case _ => None
        }
      case _ => Nil

  def diffsFromRawInput(raw: Json): List[AcpDiffBlock] =
    raw match
      case obj: Json.Obj =>
        obj.get("path") match
          case Some(Json.Str(path)) =>
            val hasOld = obj.get("old_string").isDefined || obj.get("oldText").isDefined
            val hasNew =
              obj.get("new_string").isDefined || obj.get("newText").isDefined || obj.get("contents").isDefined
            if !hasOld && !hasNew then Nil
            else
              val oldRaw         = obj.get("old_string").orElse(obj.get("oldText"))
              val newRaw         = obj.get("new_string").orElse(obj.get("newText")).orElse(obj.get("contents"))
              val oldTextWasNull = !hasOld || oldRaw.contains(Json.Null)
              List(
                AcpDiffBlock(
                  path,
                  oldRaw.collect { case Json.Str(s) => s }.getOrElse(""),
                  newRaw.collect { case Json.Str(s) => s }.getOrElse(""),
                  oldTextWasNull,
                )
              )
            end if
          case _ => Nil
      case _ => Nil

  def replaceAllFromRawInput(raw: Json): Boolean =
    raw match
      case obj: Json.Obj =>
        obj.get("replace_all") match
          case Some(Json.Bool(true)) => true
          case _                     => false
      case _ => false

  def fromPathFromRawInput(raw: Json): Option[String] =
    raw match
      case obj: Json.Obj =>
        stringField(obj, "from_path")
          .orElse(stringField(obj, "fromPath"))
          .orElse(stringField(obj, "from"))
          .orElse(
            if stringField(obj, "destination").isDefined then stringField(obj, "source") else None
          )
      case _ => None

  def locationsFrom(value: Json): List[ToolLocation] =
    value match
      case Json.Arr(items) =>
        items.toList.flatMap {
          case obj: Json.Obj =>
            stringField(obj, "path").map { path =>
              val line = obj.get("line") match
                case Some(Json.Num(n)) if n.intValue > 0 => Some(n.intValue)
                case _                                   => None
              ToolLocation(path, line)
            }
          case _ => None
        }
      case _ => Nil

  def toolCallFromPermission(params: Json): Json =
    params match
      case obj: Json.Obj =>
        obj.get("toolCall") match
          case Some(tc) => tc
          case _        => params
      case _ => params

  def diffsFromToolCall(toolCall: Json): ToolCallDiffs =
    val rec = toolCall match
      case obj: Json.Obj => obj
      case _             => Json.Obj()
    val contentDiffs = rec.get("content").toList.flatMap(diffsFromContent)
    val raw          = rec.get("rawInput").getOrElse(Json.Obj())
    val rawDiffs     = diffsFromRawInput(raw)
    ToolCallDiffs(
      toolCallId = stringField(rec, "toolCallId").getOrElse("tool"),
      title = stringField(rec, "title").getOrElse("Tool"),
      kind = stringField(rec, "kind").getOrElse("other"),
      status = stringField(rec, "status").getOrElse("pending"),
      diffs = if contentDiffs.nonEmpty then contentDiffs else rawDiffs,
      replaceAll = replaceAllFromRawInput(raw),
      fromPath = fromPathFromRawInput(raw),
      locations = rec.get("locations").toList.flatMap(locationsFrom),
    )
  end diffsFromToolCall

  def diskIsBefore(status: String): Boolean = status != "completed"

  def inferKind(
      toolKind: String,
      fromPath: Option[String],
      oldText: String,
      newText: String,
      diskExists: Boolean,
      diskIsBeforeWrite: Boolean,
  ): ChangeKind =
    if fromPath.isDefined || toolKind == "move" then ChangeKind.Move
    else if toolKind == "delete" then if !diskIsBeforeWrite && diskExists then ChangeKind.Modify else ChangeKind.Delete
    else if oldText.isEmpty && newText.nonEmpty then ChangeKind.Add
    else ChangeKind.Modify

  def expandAcp(
      diff: AcpDiffBlock,
      diskText: Option[String],
      diskIsBeforeWrite: Boolean,
      replaceAll: Boolean,
  ): DiffSides =
    DiffExpand.expand(
      DiffExpandInput(
        diskText = diskText,
        oldRegion = diff.oldText,
        newRegion = diff.newText,
        diskIsBefore = diskIsBeforeWrite,
        replaceAll = replaceAll,
      )
    )

  def reconstruct(
      toolCall: Json,
      readDisk: String => Option[String],
      diskIsBeforeWrite: Boolean,
  ): List[ReconstructedFileDiff] =
    val extracted = diffsFromToolCall(toolCall)
    extracted.diffs.map { diff =>
      val disk     = readDisk(diff.path)
      val expanded = expandAcp(diff, disk, diskIsBeforeWrite, extracted.replaceAll)
      val sides    = completePostWrite(expanded, diskIsBeforeWrite, disk, diff.oldText, diff.newText, extracted.kind)
      val standIn  = !expanded.wholeFile && sides.wholeFile
      val kind     = inferKind(
        extracted.kind,
        extracted.fromPath,
        sides.oldText,
        sides.newText,
        disk.isDefined,
        diskIsBeforeWrite,
      )
      ReconstructedFileDiff(
        path = diff.path,
        oldText = sides.oldText,
        newText = sides.newText,
        firstChangedLine = sides.firstChangedLine,
        wholeFile = sides.wholeFile,
        kind = kind,
        toolCallId = extracted.toolCallId,
        fromPath = extracted.fromPath,
        regionStandIn = standIn,
      )
    }
  end reconstruct

  def fileChangeFrom(diff: ReconstructedFileDiff): FileChange =
    val (add, del) = ChangeSet.lineDiffStats(diff.oldText, diff.newText)
    FileChange(
      path = diff.path,
      kind = diff.kind,
      additions = add,
      deletions = del,
      wholeFile = diff.wholeFile,
      toolCallId = diff.toolCallId,
      fromPath = diff.fromPath,
      oldSnapshot = if diff.kind == ChangeKind.Add then None else Some(diff.oldText),
      newSnapshot = if diff.kind == ChangeKind.Delete then None else Some(diff.newText),
      undoDisabled = ChangeSet.undoDisabledFor(diff.kind, diff.wholeFile, snapshotStored = true),
    )
  end fileChangeFrom

  def permissionCard(params: Json, requestId: String): PermissionCard =
    val rec = params match
      case obj: Json.Obj => obj
      case _             => Json.Obj()
    val toolCall = rec.get("toolCall") match
      case Some(o: Json.Obj) => o
      case _                 => rec
    val toolId  = stringField(toolCall, "toolCallId").getOrElse(requestId)
    val title   = stringField(toolCall, "title").getOrElse("Permission")
    val options = rec.get("options") match
      case Some(Json.Arr(items)) =>
        items.toList.flatMap {
          case o: Json.Obj =>
            stringField(o, "optionId").map { id =>
              PermissionOption(
                id,
                stringField(o, "name").getOrElse(id),
                stringField(o, "kind").getOrElse("other"),
              )
            }
          case _ => None
        }
      case _ => Nil
    val hasDiff = diffsFromToolCall(toolCall).diffs.nonEmpty ||
      rec.get("toolCall").exists {
        case o: Json.Obj => o.get("content").exists(c => c.toJson.contains("\"type\":\"diff\""))
        case _           => false
      }
    PermissionCard(requestId, toolId, title, options, hasDiff)
  end permissionCard

  private def completePostWrite(
      sides: DiffSides,
      diskIsBeforeWrite: Boolean,
      diskText: Option[String],
      oldRegion: String,
      newRegion: String,
      toolKind: String,
  ): DiffSides =
    if diskIsBeforeWrite || sides.wholeFile || newRegion.nonEmpty then sides
    else if diskText.contains("") then DiffSides(oldRegion, "", 0, wholeFile = true)
    else if toolKind == "delete" && diskText.isEmpty && oldRegion.nonEmpty then
      DiffSides(oldRegion, "", 0, wholeFile = true)
    else sides

  private def stringField(obj: Json.Obj, key: String): Option[String] =
    obj.get(key) match
      case Some(Json.Str(s)) => Some(s)
      case _                 => None
end DiffContent
