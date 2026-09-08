package groksbeard.core

import zio.json.ast.Json

final case class ForkArgs(worktree: Option[Boolean], directive: Option[String])

object Fork:
  val Usage: String           = "Usage: /fork [--worktree|--no-worktree] [directive]"
  val MissingCli: String      = "CLI does not advertise."
  val MissingWorktree: String = "CLI does not advertise worktrees."
  val NoSession: String       = "No session to fork"
  val Exclusive: String       = "--worktree and --no-worktree are mutually exclusive"
  val WorktreeTwice: String   = "--worktree specified twice"
  val NoWorktreeTwice: String = "--no-worktree specified twice"
  val AtUnsupported: String   = "--at is not supported in this version"

  def parse(args: String): Either[String, ForkArgs] =
    var worktree: Option[Boolean] = None
    var rest                      = args.trim
    var err                       = Option.empty[String]
    var flags                     = true
    while flags && err.isEmpty && rest.nonEmpty do
      val i             = rest.indexWhere(_.isWhitespace)
      val (flag, after) =
        if i < 0 then (rest, "")
        else (rest.take(i), rest.drop(i).trim)
      flag match
        case "--worktree" =>
          if worktree.contains(false) then err = Some(Exclusive)
          else if worktree.contains(true) then err = Some(WorktreeTwice)
          else
            worktree = Some(true)
            rest = after
        case "--no-worktree" =>
          if worktree.contains(true) then err = Some(Exclusive)
          else if worktree.contains(false) then err = Some(NoWorktreeTwice)
          else
            worktree = Some(false)
            rest = after
        case "--at" =>
          err = Some(AtUnsupported)
        case _ =>
          flags = false
      end match
    end while
    err match
      case Some(e) => Left(e)
      case None    =>
        val directive = Option(rest).map(_.trim).filter(_.nonEmpty)
        Right(ForkArgs(worktree, directive))
  end parse

  def offersWorktree(json: Json): Boolean =
    json.toString.toLowerCase.contains("git/worktree")
end Fork
