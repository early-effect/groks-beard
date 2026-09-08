package groksbeard.core

import zio.json.ast.Json
import zio.test.*

object ForkSpec extends ZIOSpecDefault:
  def spec =
    suite("Fork")(
      test("parse empty args") {
        assertTrue(Fork.parse("") == Right(ForkArgs(None, None)))
      },
      test("parse directive only") {
        assertTrue(
          Fork.parse("explore the rate-limit hypothesis") ==
            Right(ForkArgs(None, Some("explore the rate-limit hypothesis")))
        )
      },
      test("parse worktree flags") {
        assertTrue(
          Fork.parse("--worktree") == Right(ForkArgs(Some(true), None)),
          Fork.parse("--no-worktree") == Right(ForkArgs(Some(false), None)),
          Fork.parse("--worktree investigate the bug") ==
            Right(ForkArgs(Some(true), Some("investigate the bug"))),
          Fork.parse("--no-worktree quick fix") == Right(ForkArgs(Some(false), Some("quick fix"))),
        )
      },
      test("parse rejects exclusive and duplicate flags") {
        assertTrue(
          Fork.parse("--worktree --no-worktree x") == Left(Fork.Exclusive),
          Fork.parse("--no-worktree --worktree x") == Left(Fork.Exclusive),
          Fork.parse("--worktree --worktree foo") == Left(Fork.WorktreeTwice),
        )
      },
      test("parse treats unknown tokens as the directive") {
        assertTrue(Fork.parse("--foo bar") == Right(ForkArgs(None, Some("--foo bar"))))
      },
      test("parse rejects --at") {
        assertTrue(Fork.parse("--at 3 directive") == Left(Fork.AtUnsupported))
      },
      test("offersWorktree reads initialize json") {
        val yes = Json.Obj(
          "agentCapabilities" -> Json.Obj(
            "_meta" -> Json.Obj("x.ai/git/worktree/create" -> Json.Bool(true))
          )
        )
        val no = Json.Obj("agentCapabilities" -> Json.Obj("loadSession" -> Json.Bool(true)))
        assertTrue(Fork.offersWorktree(yes), !Fork.offersWorktree(no))
      },
    )
end ForkSpec
