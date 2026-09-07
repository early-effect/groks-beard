package groksbeard.core

import zio.*
import BeardError.orSystem

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

object ProcessCapture:
  def run(command: String, args: List[String], cwd: String): BeardError.Result[String] =
    ZIO.attemptBlocking {
      val pb = new ProcessBuilder((command :: args).asJava)
      pb.directory(new java.io.File(cwd))
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val text = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if !proc.waitFor(30, TimeUnit.SECONDS) then
        proc.destroyForcibly()
        throw new RuntimeException(s"$command timed out")
      text
    }.orSystem
end ProcessCapture
