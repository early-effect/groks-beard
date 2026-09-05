package groksbeard.core

import java.nio.file.{Files, Path}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import zio.*
import BeardError.orSystem

object NioSessionFs extends SessionFs:
  def listNames(dir: String): BeardError.Result[List[String]] =
    ZIO.attemptBlocking {
      val path = Path.of(dir)
      if !Files.isDirectory(path) then Nil
      else
        val stream = Files.list(path)
        try stream.iterator.asScala.map(_.getFileName.toString).toList
        finally stream.close()
    }.orSystem

  def isDirectory(path: String): BeardError.Result[Boolean] =
    ZIO.attemptBlocking(Files.isDirectory(Path.of(path))).orSystem

  def mtimeMs(path: String): BeardError.Result[Option[Long]] =
    ZIO.attemptBlocking {
      val p = Path.of(path)
      if !Files.exists(p) then None
      else Some(Files.getLastModifiedTime(p).toMillis)
    }.orSystem

  def readText(path: String): BeardError.Result[Option[String]] =
    ZIO.attemptBlocking {
      val p = Path.of(path)
      if !Files.isRegularFile(p) then None
      else Some(Files.readString(p))
    }.orSystem

  override def writeText(path: String, text: String): BeardError.Result[Unit] =
    ZIO.attemptBlocking {
      val p = Path.of(path)
      Option(p.getParent).foreach(parent => Files.createDirectories(parent))
      Files.writeString(p, text)
      ()
    }.orSystem

  override def deleteTree(path: String): BeardError.Result[Unit] =
    ZIO.attemptBlocking {
      val p = Path.of(path)
      if Files.exists(p) then
        val walk = Files.walk(p)
        try walk.sorted(Comparator.reverseOrder()).forEach(Files.delete)
        finally walk.close()
    }.orSystem
end NioSessionFs

object SessionWalk:
  def fromDisk(home: String, cwd: String, limit: Int = SessionIndex.PageSize): BeardError.Result[List[SessionRow]] =
    SessionIndex.listRows(NioSessionFs, home, cwd, limit)
