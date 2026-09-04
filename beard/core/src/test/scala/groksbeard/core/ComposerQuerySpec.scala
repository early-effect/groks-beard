package groksbeard.core

import zio.test.*

object ComposerQuerySpec extends ZIOSpecDefault:
  private val commands = List(
    SlashCommand("compact", "Compact context"),
    SlashCommand("always-approve", "Skip permission prompts"),
    SlashCommand("init", "Initialize project memory"),
  )

  def spec =
    suite("ComposerQuery")(
      test("filters slash commands prefix, then mid-name, then description") {
        assertTrue(
          ComposerQuery.filterSlash(commands, "always").map(_.name) == List("always-approve"),
          ComposerQuery.filterSlash(commands, "approve").map(_.name) == List("always-approve"),
          ComposerQuery.filterSlash(commands, "memory").map(_.name) == List("init"),
        )
      },
      test("does not hide always-approve") {
        assertTrue(ComposerQuery.filterSlash(commands, "").map(_.name).contains("always-approve"))
      },
      test("reads a slash query from the composer draft") {
        assertTrue(
          ComposerQuery.slashQuery("/comp") == Some("comp"),
          ComposerQuery.slashQuery("hello").isEmpty,
        )
      },
      test("reads a trailing @ token from the draft") {
        assertTrue(
          ComposerQuery.mentionQuery("@") == Some(""),
          ComposerQuery.mentionQuery("see @src/") == Some("src/"),
          ComposerQuery.mentionQuery("@Main.scala") == Some("Main.scala"),
          ComposerQuery.mentionQuery("hello").isEmpty,
          ComposerQuery.mentionQuery("@done ").isEmpty,
        )
      },
      test("closes the mention popover when @ is gone or dismissed") {
        assertTrue(
          ComposerQuery.mentionPopoverOpen("@fi", dismissed = false),
          !ComposerQuery.mentionPopoverOpen("@fi", dismissed = true),
          !ComposerQuery.mentionPopoverOpen("no mention", dismissed = false),
        )
      },
      test("keeps mention choices only for the live @ query") {
        val files = List(MentionFile("a.ts", "/a.ts"))
        assertTrue(
          ComposerQuery.mentionChoices("@a", "a", files, dismissed = false) == files,
          ComposerQuery.mentionChoices("done", "a", files, dismissed = false).isEmpty,
          ComposerQuery.mentionChoices("@a", "ab", files, dismissed = false).isEmpty,
        )
      },
      test("moves the mention highlight from the keyboard") {
        assertTrue(
          ComposerQuery.moveMentionIndex(None, "ArrowUp", 3) == Some(0),
          ComposerQuery.moveMentionIndex(None, "ArrowDown", 3) == Some(0),
          ComposerQuery.moveMentionIndex(Some(0), "ArrowDown", 3) == Some(1),
          ComposerQuery.moveMentionIndex(Some(0), "ArrowUp", 3) == Some(0),
          ComposerQuery.moveMentionIndex(Some(2), "ArrowDown", 3) == Some(2),
          ComposerQuery.moveMentionIndex(Some(0), "ArrowUp", 0).isEmpty,
        )
      },
      test("maps 1-9 onto permission options") {
        val opts = List(
          PermissionOption("allow", "Allow", "allow_once"),
          PermissionOption("reject", "Reject", "reject_once"),
        )
        assertTrue(
          ComposerQuery.permissionOption("1", opts).map(_.optionId).contains("allow"),
          ComposerQuery.permissionOption("2", opts).map(_.optionId).contains("reject"),
          ComposerQuery.permissionOption("3", opts).isEmpty,
          ComposerQuery.permissionOption("a", opts).isEmpty,
        )
      },
      test("maps 1-9 and a-f onto the first question") {
        val questions = List(
          AgentQuestion(
            "style",
            "How?",
            List(QuestionOption("dense", "Dense"), QuestionOption("roomy", "Roomy")),
          )
        )
        assertTrue(
          ComposerQuery.questionOption("1", questions).contains(("style", "dense")),
          ComposerQuery.questionOption("2", questions).contains(("style", "roomy")),
          ComposerQuery.questionOption("a", questions).isEmpty,
        )
      },
      test("Enter sends unless ctrlEnterToSend, Shift+Enter inserts a newline, Ctrl/Cmd+Enter always sends") {
        assertTrue(
          ComposerQuery.sendOnKey("Enter", shift = false, ctrlOrMeta = false, ctrlEnterToSend = false) ==
            ComposerQuery.SendKey.Send,
          ComposerQuery.sendOnKey("Enter", shift = true, ctrlOrMeta = false, ctrlEnterToSend = false) ==
            ComposerQuery.SendKey.Newline,
          ComposerQuery.sendOnKey("Enter", shift = false, ctrlOrMeta = true, ctrlEnterToSend = false) ==
            ComposerQuery.SendKey.Send,
          ComposerQuery.sendOnKey("Enter", shift = false, ctrlOrMeta = false, ctrlEnterToSend = true) ==
            ComposerQuery.SendKey.Newline,
          ComposerQuery.sendOnKey("Enter", shift = false, ctrlOrMeta = true, ctrlEnterToSend = true) ==
            ComposerQuery.SendKey.Send,
          ComposerQuery.sendOnKey("a", shift = false, ctrlOrMeta = false, ctrlEnterToSend = false) ==
            ComposerQuery.SendKey.Ignore,
        )
      },
    )
end ComposerQuerySpec
