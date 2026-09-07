package groksbeard.core

import zio.*

/** One log line format. Sinks: VS Code OutputChannel, preview console/stderr. */
object BeardLog:
  def line(level: LogLevel, message: String, cause: Cause[Any] = Cause.empty): String =
    val extra = if cause.isEmpty then "" else s"\n${cause.prettyPrint}"
    s"[${level.label}] $message$extra"

  def logger(write: String => Unit): ZLogger[String, Unit] =
    (
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String],
    ) =>
      val _ = (trace, fiberId, context, spans, annotations)
      write(line(logLevel, message(), cause))
end BeardLog
