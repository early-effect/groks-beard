package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object DiffContentSpec extends ZIOSpecDefault:
  def spec =
    suite("DiffContent")(
      test("diffsFromContent reads type=diff blocks and treats missing oldText as null") {
        val content = Json.Arr(
          Json.Obj(
            "type"    -> Json.Str("diff"),
            "path"    -> Json.Str("/a.ts"),
            "newText" -> Json.Str("new"),
          ),
          Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("ignore")),
        )
        val diffs = DiffContent.diffsFromContent(content)
        assertTrue(diffs == List(AcpDiffBlock("/a.ts", "", "new", oldTextWasNull = true)))
      },
      test("diffsFromRawInput reads old_string / new_string") {
        val raw = Json.Obj(
          "path"        -> Json.Str("/a.ts"),
          "old_string"  -> Json.Str("old"),
          "new_string"  -> Json.Str("new"),
          "replace_all" -> Json.Bool(true),
        )
        assertTrue(
          DiffContent.diffsFromRawInput(raw) == List(AcpDiffBlock("/a.ts", "old", "new", oldTextWasNull = false)),
          DiffContent.replaceAllFromRawInput(raw),
        )
      },
      test("content diffs win over rawInput") {
        val tool = Json.Obj(
          "toolCallId" -> Json.Str("c1"),
          "content"    -> Json.Arr(
            Json.Obj(
              "type"    -> Json.Str("diff"),
              "path"    -> Json.Str("/a.ts"),
              "oldText" -> Json.Str("a"),
              "newText" -> Json.Str("b"),
            )
          ),
          "rawInput" -> Json.Obj(
            "path"       -> Json.Str("/ignored.ts"),
            "old_string" -> Json.Str("x"),
            "new_string" -> Json.Str("y"),
          ),
        )
        assertTrue(DiffContent.diffsFromToolCall(tool).diffs.head.path == "/a.ts")
      },
      test("inferKind: empty old + new body is add, delete tool stays delete") {
        assertTrue(
          DiffContent.inferKind("edit", None, "", "hi", diskExists = false, diskIsBeforeWrite = true) == ChangeKind.Add,
          DiffContent
            .inferKind("delete", None, "body", "", diskExists = false, diskIsBeforeWrite = true) == ChangeKind.Delete,
          DiffContent
            .inferKind("move", Some("/from"), "a", "a", diskExists = true, diskIsBeforeWrite = true) == ChangeKind.Move,
        )
      },
      test("reconstruct with disk expands a region to the whole file") {
        val tool = Json.Obj(
          "toolCallId" -> Json.Str("c1"),
          "kind"       -> Json.Str("edit"),
          "status"     -> Json.Str("pending"),
          "content"    -> Json.Arr(
            Json.Obj(
              "type"    -> Json.Str("diff"),
              "path"    -> Json.Str("/tmp/Main.scala"),
              "oldText" -> Json.Str("old"),
              "newText" -> Json.Str("new"),
            )
          ),
        )
        val diffs =
          DiffContent.reconstruct(tool, p => if p == "/tmp/Main.scala" then Some("aaa\nold\nccc\n") else None, true)
        assertTrue(
          diffs.head.wholeFile,
          diffs.head.oldText == "aaa\nold\nccc\n",
          diffs.head.newText == "aaa\nnew\nccc\n",
          diffs.head.kind == ChangeKind.Modify,
        )
      },
      test("permissionCard sets hasDiff from toolCall content") {
        val params = Json.Obj(
          "toolCall" -> Json.Obj(
            "toolCallId" -> Json.Str("edit-1"),
            "title"      -> Json.Str("Edit Main.scala"),
            "content"    -> Json.Arr(
              Json.Obj(
                "type"    -> Json.Str("diff"),
                "path"    -> Json.Str("/a.ts"),
                "oldText" -> Json.Str("a"),
                "newText" -> Json.Str("b"),
              )
            ),
          ),
          "options" -> Json.Arr(
            Json.Obj("optionId" -> Json.Str("allow"), "name" -> Json.Str("Allow"), "kind" -> Json.Str("allow_once"))
          ),
        )
        val card = DiffContent.permissionCard(params, "perm-1")
        assertTrue(card.hasDiff, card.toolCallId == "edit-1", card.options.head.optionId == "allow")
      },
    )
end DiffContentSpec
