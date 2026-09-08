package groksbeard.host

import groksbeard.host.vscode.OutputChannel

import groksbeard.facade.jsStack

import scala.scalajs.js.JavaScriptException

object HostErr:
  def format(e: Throwable): String =
    val head = e match
      case _: JavaScriptException =>
        e.jsStack.getOrElse(e.toString)
      case other =>
        val msg = Option(other.getMessage).filter(_.nonEmpty).getOrElse(other.toString)
        val st  = other.getStackTrace.mkString("\n")
        if st.isEmpty then s"${other.getClass.getName}: $msg" else s"${other.getClass.getName}: $msg\n$st"
    val caused =
      Option(e.getCause).filter(c => (c ne e) && (c ne null)).map(c => "\nCaused by: " + format(c)).getOrElse("")
    head + caused
  end format
end HostErr

final class BeardOut(ch: OutputChannel):
  def line(msg: String): Unit = ch.appendLine(msg)

  def error(where: String, e: Throwable): String =
    val text = HostErr.format(e)
    ch.appendLine(s"[error] $where")
    ch.appendLine(text)
    ch.show(true)
    text
end BeardOut
