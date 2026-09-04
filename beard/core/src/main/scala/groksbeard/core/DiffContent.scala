package groksbeard.core

import zio.json.*
import zio.json.ast.Json

final case class AcpDiffBlock(
    path: String,
    oldText: String,
    newText: String,
    oldTextWasNull: Boolean,
)

final case class ToolLocation(path: String, line: Option[Int] = None) derives JsonCodec

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
    content.as[List[AcpContent]] match
      case Right(items) =>
        items.collect { case AcpContent.Diff(path, oldText, newText) =>
          AcpDiffBlock(path, oldText.getOrElse(""), newText.getOrElse(""), oldText.isEmpty)
        }
      case Left(_) => Nil

  def diffsFromRawInput(raw: Json): List[AcpDiffBlock] =
    raw.as[RawEditInput] match
      case Right(in) =>
        in.path match
          case None       => Nil
          case Some(path) =>
            val hasOld = in.old_string.isDefined || in.oldText.isDefined
            val hasNew = in.new_string.isDefined || in.newText.isDefined || in.contents.isDefined
            if !hasOld && !hasNew then Nil
            else
              List(
                AcpDiffBlock(
                  path,
                  in.old_string.orElse(in.oldText).getOrElse(""),
                  in.new_string.orElse(in.newText).orElse(in.contents).getOrElse(""),
                  !hasOld,
                )
              )
            end if
      case Left(_) => Nil

  def replaceAllFromRawInput(raw: Json): Boolean =
    raw.as[RawEditInput].toOption.exists(_.replace_all.contains(true))

  def fromPathFromRawInput(raw: Json): Option[String] =
    raw.as[RawEditInput].toOption.flatMap { in =>
      in.from_path
        .orElse(in.fromPath)
        .orElse(in.from)
        .orElse(
          if in.destination.isDefined then in.source else None
        )
    }

  def locationsFrom(value: Json): List[ToolLocation] =
    value.as[List[ToolLocation]].getOrElse(Nil)

  def toolCallFromPermission(params: Json): Json =
    params.as[PermissionRequestParams] match
      case Right(p) => p.toolCall.asJson
      case Left(_)  => params

  def diffsFromToolCall(toolCall: Json): ToolCallDiffs =
    val body         = toolCall.as[AcpToolCall].getOrElse(AcpToolCall())
    val contentDiffs = body.content.collect { case AcpContent.Diff(path, oldText, newText) =>
      AcpDiffBlock(path, oldText.getOrElse(""), newText.getOrElse(""), oldText.isEmpty)
    }
    val raw      = body.rawInput.getOrElse(Json.Obj())
    val rawDiffs = diffsFromRawInput(raw)
    ToolCallDiffs(
      toolCallId = body.toolCallId,
      title = body.title,
      kind = body.kind,
      status = body.status,
      diffs = if contentDiffs.nonEmpty then contentDiffs else rawDiffs,
      replaceAll = replaceAllFromRawInput(raw),
      fromPath = fromPathFromRawInput(raw),
      locations = body.locations,
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
    val parsed = params
      .as[PermissionRequestParams]
      .getOrElse(PermissionRequestParams(toolCall = AcpToolCall(toolCallId = requestId)))
    val tool = parsed.toolCall
    PermissionCard(
      requestId,
      if tool.toolCallId.nonEmpty then tool.toolCallId else requestId,
      if tool.title.nonEmpty then tool.title else "Permission",
      parsed.options,
      hasDiff = diffsFromToolCall(tool.asJson).diffs.nonEmpty,
    )
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

end DiffContent
