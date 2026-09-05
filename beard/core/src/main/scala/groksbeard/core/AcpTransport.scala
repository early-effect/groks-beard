package groksbeard.core

import zio.*

trait AcpTransport:
  def write(data: String): BeardError.Result[Unit]
  def attach(ingest: String => UIO[Unit]): UIO[Unit]
  def close: UIO[Unit]

object AcpTransport:
  def fake(agent: FakeAgent = FakeAgent()): AcpTransport =
    new AcpTransport:
      private var ingest: String => UIO[Unit] = _ => ZIO.unit

      def attach(next: String => UIO[Unit]): UIO[Unit] =
        ZIO.succeed { ingest = next }

      def write(data: String): BeardError.Result[Unit] =
        val (lines, _) = Ndjson.split("", data)
        ZIO.foreachDiscard(lines) { line =>
          Rpc.parse(line) match
            case Right(msg) => ingest(agent.encodeReplies(msg))
            case Left(_)    => ZIO.unit
        }

      def close: UIO[Unit] = ZIO.unit

  def tap(inner: AcpTransport, onWrite: String => Unit): AcpTransport =
    new AcpTransport:
      def attach(ingest: String => UIO[Unit]): UIO[Unit] = inner.attach(ingest)
      def write(data: String): BeardError.Result[Unit]   = ZIO.succeed(onWrite(data)) *> inner.write(data)
      def close: UIO[Unit]                               = inner.close
end AcpTransport
