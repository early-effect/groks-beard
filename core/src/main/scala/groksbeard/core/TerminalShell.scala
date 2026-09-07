package groksbeard.core

/** POSIX spawn for ACP `terminal/create`.
  *
  * Grok 1.0.x still sends `/bin/bash -lc <script>` even when the host is zsh. Wrapping that in `$SHELL -c` execs macOS
  * bash 3.2, which sources `~/.bash_profile` and trips sdkman (`${candidate_name^^}`). Same diagnosis as
  * phuryn/grok-build-vscode#140 / #141: peel one login-bash wrapper and run the inner script under `$SHELL`.
  */
object TerminalShell:
  private val PosixNames     = Set("sh", "bash", "zsh", "ksh", "ksh93", "mksh", "dash", "ash")
  private val BashCapable    = Set("bash", "zsh")
  private val BareCommand    = raw"^[A-Za-z0-9_@%+=:,./-]+$$".r
  private val EnvPrefix      = raw"^/usr/bin/env\s+".r
  private val FlagTok        = raw"^(--[a-zA-Z0-9-]+|-[a-zA-Z]+)(?:\s+|$$)".r
  private val SpecialInQuote = "$`\"\\"

  def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  def hostShell(envShell: Option[String] = sys.env.get("SHELL")): String =
    posixShellFromEnv(envShell).getOrElse("/bin/sh")

  /** Absolute POSIX-capable `$SHELL`, else none (caller uses `/bin/sh`). */
  def posixShellFromEnv(shell: Option[String]): Option[String] =
    shell.map(_.trim).filter(_.startsWith("/")).flatMap { trimmed =>
      if trimmed == "/bin/sh" || trimmed == "/usr/bin/sh" then None
      else
        val name = trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase
        if PosixNames.contains(name) then Some(trimmed) else None
    }

  def grokShellEnv(host: String): Option[String] =
    if host == "/bin/sh" || host == "/usr/bin/sh" then None else Some(host)

  def argv(command: String, args: List[String], host: String = hostShell()): List[String] =
    if isWindows then command :: args
    else posixArgv(command, args, host)

  def posixArgv(command: String, args: List[String], host: String): List[String] =
    val base    = host.substring(host.lastIndexOf('/') + 1).toLowerCase
    val capable = BashCapable.contains(base)
    args match
      case Nil =>
        val script =
          if capable then unwrapGrokBashLoginWrapper(command).getOrElse(command) else command
        List(host, "-c", script)
      case flags =>
        if capable then
          unwrapGrokBashLoginArgs(command, flags) match
            case Some(script) => List(host, "-c", script)
            case None         => command :: flags
        else command :: flags
    end match
  end posixArgv

  /** Peel grok's `/bin/bash -lc <script>` wrapper. One layer only. */
  def unwrapGrokBashLoginWrapper(command: String): Option[String] =
    var s = stripBlanks(command)
    EnvPrefix.findPrefixOf(s).foreach { p => s = s.substring(p.length) }
    val sp           = s.indexWhere(c => c == ' ' || c == '\t' || c == '\n')
    val (exe, after) =
      if sp < 0 then (s, "")
      else (s.substring(0, sp), s.substring(sp))
    val base = exe.substring(exe.lastIndexOf('/') + 1)
    if base != "bash" then None
    else unwrapFlagsAndScript(stripBlanks(after))
  end unwrapGrokBashLoginWrapper

  def unwrapGrokBashLoginArgs(command: String, args: List[String]): Option[String] =
    val exe  = command.trim
    val base = exe.substring(exe.lastIndexOf('/') + 1)
    val rest =
      if base == "env" && args.headOption.exists(_.endsWith("bash")) then args.drop(1)
      else if base == "bash" then args
      else Nil
    if rest.isEmpty then None else unwrapFlagsAndScript(rest.mkString(" "))

  def parseOneShellWord(s: String): Option[(String, String)] =
    val word = StringBuilder()
    var i    = 0
    while i < s.length do
      val ch = s.charAt(i)
      if ch == ' ' || ch == '\t' || ch == '\n' then return Some((word.toString, s.substring(i)))
      else if ch == '\'' then
        val close = s.indexOf('\'', i + 1)
        if close < 0 then return None
        word.append(s.substring(i + 1, close))
        i = close + 1
      else if ch == '"' then
        i += 1
        var closed = false
        while i < s.length && !closed do
          if s.charAt(i) == '"' then
            closed = true
            i += 1
          else if s.charAt(i) == '\\' && i + 1 < s.length then
            val n = s.charAt(i + 1)
            if n == '\n' then i += 2
            else if SpecialInQuote.contains(n) then
              word.append(n)
              i += 2
            else
              word.append(s.charAt(i))
              i += 1
          else
            word.append(s.charAt(i))
            i += 1
        end while
        if !closed then return None
      else if ch == '\\' then
        if i + 1 >= s.length then return None
        if s.charAt(i + 1) == '\n' then i += 2
        else
          word.append(s.charAt(i + 1))
          i += 2
      else
        word.append(ch)
        i += 1
      end if
    end while
    Some((word.toString, s.substring(i)))
  end parseOneShellWord

  private def unwrapFlagsAndScript(after: String): Option[String] =
    def loop(s0: String, sawLogin: Boolean, sawCommand: Boolean): Option[String] =
      val s = stripBlanks(s0)
      if sawCommand then finishScript(sawLogin, sawCommand, s)
      else if !s.startsWith("-") then finishScript(sawLogin, sawCommand, s)
      else
        FlagTok.findPrefixMatchOf(s) match
          case None    => None
          case Some(m) =>
            val tok  = m.group(1)
            val next = stripBlanks(s.substring(m.group(0).length))
            if tok == "--login" then loop(next, true, sawCommand)
            else if tok.startsWith("-") && tok.length > 1 && !tok.startsWith("--") then
              val chars = tok.substring(1)
              if chars.exists(ch => ch != 'l' && ch != 'c') then None
              else loop(next, sawLogin || chars.contains('l'), sawCommand || chars.contains('c'))
            else None
      end if
    end loop
    loop(after, false, false)
  end unwrapFlagsAndScript

  private def finishScript(sawLogin: Boolean, sawCommand: Boolean, s: String): Option[String] =
    if !sawLogin || !sawCommand || s.isEmpty then None
    else if s.charAt(0) != '\'' && s.charAt(0) != '"' then if BareCommand.matches(s) then Some(s) else None
    else
      parseOneShellWord(s).flatMap { (word, rest) =>
        if stripBlanks(rest).isEmpty then Some(word) else None
      }

  private def stripBlanks(v: String): String =
    var i = 0
    while i < v.length && (v.charAt(i) == ' ' || v.charAt(i) == '\t' || v.charAt(i) == '\n') do i += 1
    v.substring(i)
end TerminalShell
