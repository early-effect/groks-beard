package groksbeard.core

import zio.json.*

final case class DoctorFinding(
    id: String,
    ok: Boolean,
    label: String,
    detail: String,
    na: Boolean = false,
    fix: Option[String] = None,
) derives JsonCodec

object Doctor:
  val TtyNa: String = "Not applicable in the editor"

  def collect(
      cliPath: Option[String],
      version: Option[String],
      embeddedContext: Boolean,
      image: Boolean,
      sessionList: Boolean,
      terminal: Boolean,
      clipboardOk: Boolean,
      sessionDir: String,
      mcpCount: Int,
      voice: String,
      nodePath: Option[String],
  ): List[DoctorFinding] =
    List(
      finding(
        "cli",
        cliPath.exists(_.nonEmpty),
        "CLI",
        cliPath.filter(_.nonEmpty).map(p => version.map(v => s"$p ($v)").getOrElse(p)).getOrElse("grok not found"),
        fix = Some("Install Grok Build and ensure grok is on PATH, or set groksBeard.cliPath."),
      ),
      finding("node", nodePath.exists(_.nonEmpty), "Node", nodePath.getOrElse("not required for ACP stdio")),
      finding(
        "embedded",
        embeddedContext,
        "embeddedContext",
        if embeddedContext then "promptCapabilities.embeddedContext" else "not advertised",
      ),
      finding(
        "image",
        image,
        "image paste",
        if image then "promptCapabilities.image" else "not advertised; chips still attach as resource blobs",
      ),
      finding("session-list", sessionList, "session/list", if sessionList then "advertised" else "disk picker only"),
      finding("terminal", terminal, "terminal/*", if terminal then "advertised" else "not advertised"),
      finding("clipboard", clipboardOk, "Clipboard", if clipboardOk then "ok" else "webview clipboard may be blocked"),
      finding("sessions", sessionDir.nonEmpty, "Session dir", if sessionDir.nonEmpty then sessionDir else "unknown"),
      finding("mcp", true, "MCP servers", if mcpCount == 0 then "none listed" else s"$mcpCount listed"),
      finding("voice", voice != "missing", "Voice", voice),
      DoctorFinding("tmux", ok = true, "tmux / OSC 52 / truecolor", TtyNa, na = true),
    )

  def headline(findings: List[DoctorFinding]): String =
    val bad = findings.count(f => !f.ok && !f.na)
    if bad == 0 then "Doctor · all clear"
    else if bad == 1 then "Doctor · 1 finding"
    else s"Doctor · $bad findings"

  def fixes(findings: List[DoctorFinding]): List[DoctorFinding] =
    findings.filter(f => !f.ok && !f.na && f.fix.nonEmpty)

  private def finding(
      id: String,
      ok: Boolean,
      label: String,
      detail: String,
      fix: Option[String] = None,
  ): DoctorFinding =
    DoctorFinding(id, ok, label, detail, fix = if ok then None else fix)
end Doctor
