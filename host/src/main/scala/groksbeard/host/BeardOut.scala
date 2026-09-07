package groksbeard.host

import groksbeard.host.vscode.OutputChannel

import scala.scalajs.js
import scala.scalajs.js.JavaScriptException

object HostErr:
  def format(e: Throwable): String =
    val head = e match
      case JavaScriptException(ex) =>
        val dyn = ex.asInstanceOf[js.Dynamic]
        dyn.stack.asInstanceOf[js.UndefOr[Any]].toOption.map(_.toString).getOrElse(s"$ex")
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
