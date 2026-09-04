package groksbeard.core

trait AcpTransport:
  def write(data: String): Unit
  def onData(listener: String => Unit): Unit
  def close(): Unit

object AcpTransport:
  def fake(agent: FakeAgent = FakeAgent()): AcpTransport =
    new AcpTransport:
      private var listener: String => Unit   = _ => ()
      def onData(next: String => Unit): Unit = listener = next
      def write(data: String): Unit          =
        val (lines, _) = Ndjson.split("", data)
        lines.foreach { line =>
          Rpc.parse(line).foreach { msg =>
            listener(agent.encodeReplies(msg))
          }
        }
      def close(): Unit = ()
end AcpTransport
