package groksbeard.core

final case class CopySpec(nth: Int = 1, path: Option[String] = None)
final case class CopyJob(text: String, path: Option[String] = None)
final case class CopyResult(message: String, clipboard: Option[String] = None)

object TranscriptCopy:
  val DefaultBackupName: String = "last-copy.txt"

  def parseCopy(args: String): Either[String, CopySpec] =
    val t = args.trim
    if t.isEmpty then Right(CopySpec())
    else
      val i            = t.indexWhere(_.isWhitespace)
      val (head, rest) =
        if i < 0 then (t, "")
        else (t.take(i), t.drop(i).trim)
      if head.nonEmpty && head.forall(_.isDigit) then
        val n = head.toLong
        if n < 1 || n > Int.MaxValue then Left("nth must be a positive integer")
        else Right(CopySpec(n.toInt, Option(rest).filter(_.nonEmpty)))
      else Right(CopySpec(path = Some(t)))
    end if
  end parseCopy

  def parseExport(args: String): Either[String, Option[String]] =
    Right(Option(args.trim).filter(_.nonEmpty))

  def replies(turns: List[TurnView]): List[String] =
    turns.flatMap(t => Option(t.agent.trim).filter(_.nonEmpty)).reverse

  def reply(turns: List[TurnView], nth: Int): Either[String, String] =
    val rows = replies(turns)
    if rows.isEmpty then Left("No assistant messages to copy")
    else
      rows.lift(nth - 1) match
        case Some(text) => Right(text)
        case None       =>
          val n = rows.size
          if n == 1 then Left("Only 1 assistant message available to copy")
          else Left(s"Only $n assistant messages available to copy")
  end reply

  def conversation(turns: List[TurnView]): Option[String] =
    val blocks = turns.flatMap { t =>
      val user  = t.user.map(_.text.trim).filter(_.nonEmpty).map(u => s"## User\n$u")
      val agent = Option(t.agent.trim).filter(_.nonEmpty).map(a => s"## Assistant\n$a")
      List(user, agent).flatten
    }
    if blocks.isEmpty then None else Some(blocks.mkString("\n\n") + "\n")

  def copy(turns: List[TurnView], args: String): Either[String, CopyJob] =
    parseCopy(args).flatMap { spec =>
      reply(turns, spec.nth).map(text => CopyJob(text, spec.path))
    }

  def conversationJob(turns: List[TurnView], inSession: Boolean, args: String): Either[String, CopyJob] =
    parseExport(args).flatMap { path =>
      conversation(turns) match
        case Some(text) => Right(CopyJob(text, path))
        case None       =>
          if inSession then Left("No conversation to export")
          else Left("No active session to export")
    }

  def userHome(env: String => Option[String]): String =
    env("HOME")
      .orElse(env("USERPROFILE"))
      .filter(_.nonEmpty)
      .map(_.replaceAll("[\\\\/]+$", ""))
      .getOrElse(".")

  def expandPath(raw: String, home: String, cwd: String): String =
    val t = raw.trim
    if t.isEmpty then t
    else if t == "~" then home
    else if t.startsWith("~/") || t.startsWith("~\\") then SessionIndex.join(home, t.drop(2))
    else if isAbsolute(t) then t
    else SessionIndex.join(cwd, t)

  def backupPath(env: String => Option[String], home: String, cwd: String): String =
    env("GROK_COPY_FILE").filter(_.nonEmpty) match
      case Some(p) => expandPath(p, userHome(env), cwd)
      case None    => SessionIndex.join(home, DefaultBackupName)

  def toast(path: Option[String], conversation: Boolean): String =
    path match
      case Some(p) if conversation => s"Conversation exported to $p"
      case Some(p)                 => s"Copied to $p"
      case None if conversation    => "Conversation copied to clipboard"
      case None                    => "Copied!"

  private def isAbsolute(path: String): Boolean =
    path.startsWith("/") || path.startsWith("\\") ||
      (path.length >= 3 && path.charAt(1) == ':' && (path.charAt(2) == '/' || path.charAt(2) == '\\'))
end TranscriptCopy
