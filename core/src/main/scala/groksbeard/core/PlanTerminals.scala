package groksbeard.core

/** Plan-mode policy for ACP `terminal/create`.
  *
  * Live grok 1.0.13 (5e9a58528b76), `clientCapabilities.terminal = true`, mode `plan`:
  *   - 2026-09-06: a soft `echo … > file` prompt wrote `plan.md` and `_x.ai/exit_plan_mode` (no `terminal/create`).
  *   - 2026-09-07: an explicit "do not write a plan, run this now" prompt did send mutating `terminal/create` (`echo
  *     beard-plan-probe > /tmp/beard-plan-probe.txt`). The handler rejected it; the agent quoted [[Reject]]. The file
  *     was not created.
  *
  * TUI docs say plan mode blocks edit tools, not shell redirection, and subagents do not inherit the parent's plan-file
  * gate. Keep `terminal: true` at initialize. While `planActive`, reject mutating `terminal/create` in the handler
  * (this connection, parent and subagents).
  */
object PlanTerminals:
  val DefaultByteLimit: Int = 1_048_576

  val Reject: String = "Plan mode does not run mutating shells. Approve the plan first."

  private val ReadOnly: Set[String] = Set(
    "ls",
    "dir",
    "cat",
    "head",
    "tail",
    "more",
    "less",
    "find",
    "grep",
    "egrep",
    "fgrep",
    "rg",
    "ag",
    "ack",
    "wc",
    "file",
    "stat",
    "pwd",
    "whoami",
    "id",
    "uname",
    "date",
    "hostname",
    "dirname",
    "basename",
    "realpath",
    "readlink",
    "which",
    "whence",
    "type",
    "env",
    "printenv",
    "echo",
    "printf",
    "true",
    "false",
    "test",
    "sleep",
    "seq",
    "sort",
    "uniq",
    "cut",
    "tr",
    "awk",
    "column",
    "nl",
    "od",
    "hexdump",
    "xxd",
    "md5",
    "md5sum",
    "sha1sum",
    "sha256sum",
    "git",
    "sed",
  )

  private val GitRead: Set[String] = Set(
    "status",
    "log",
    "diff",
    "show",
    "rev-parse",
    "ls-files",
    "ls-tree",
    "blame",
    "describe",
    "version",
    "help",
    "grep",
  )

  def scriptOf(command: String, args: List[String]): String =
    TerminalShell
      .unwrapGrokBashLoginWrapper(command)
      .orElse(TerminalShell.unwrapGrokBashLoginArgs(command, args))
      .getOrElse(if args.isEmpty then command else (command :: args).mkString(" "))

  def allowed(command: String, args: List[String]): Boolean =
    allowedScript(scriptOf(command, args))

  def allowedScript(script: String): Boolean =
    splitStages(script).forall(allowedSimple)

  private def splitStages(script: String): List[String] =
    val seps = raw"\s*(?:&&|\|\||;|\n)\s*".r
    seps.split(script).toList.map(_.trim).filter(_.nonEmpty)

  private def allowedSimple(stage: String): Boolean =
    val t = stage.trim
    if t.isEmpty then true
    else if hasWriteRedirect(t) then false
    else if t.contains("|") then t.split("\\|").toList.forall(allowedSimple)
    else
      val words = t.split("\\s+").toList.filter(_.nonEmpty)
      words.headOption.exists { raw =>
        val name = basename(raw)
        if name == "git" then gitRead(words.drop(1))
        else if name == "sed" then !sedInPlace(words.drop(1))
        else ReadOnly.contains(name)
      }
    end if
  end allowedSimple

  private def gitRead(args: List[String]): Boolean =
    val sub = args.dropWhile(a => a.startsWith("-") && a != "--").headOption.getOrElse("")
    GitRead.contains(sub)

  private def sedInPlace(args: List[String]): Boolean =
    args.exists(a => a == "-i" || a.startsWith("-i") || a == "--in-place" || a.startsWith("--in-place="))

  private def hasWriteRedirect(s: String): Boolean =
    val t = s
      .replaceAll("""\d?>&\d+""", " ")
      .replaceAll("""\d?>\s*/dev/null""", " ")
    t.contains(">>") || t.contains(">|") || t.contains("&>") || t.contains(">")

  private def basename(cmd: String): String =
    val cut = cmd.lastIndexOf('/')
    val raw = if cut < 0 then cmd else cmd.substring(cut + 1)
    raw.trim.toLowerCase
end PlanTerminals
