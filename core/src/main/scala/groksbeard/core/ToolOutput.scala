package groksbeard.core

/** Fold of stdout events onto one tool's output string.
  *
  * ACP `tool_call_update` content is a snapshot. Client PTY bytes are a delta. Either may arrive as
  * `HostMsg.ToolChunk`.
  */
object ToolOutput:

  def pull(prev: String, more: String, snapshot: Boolean = false): String =
    if more.isEmpty then prev
    else if prev.isEmpty then more
    else if more.startsWith(prev) then more
    else if snapshot then if prev.startsWith(more) then prev else more
    else prev + more
end ToolOutput
