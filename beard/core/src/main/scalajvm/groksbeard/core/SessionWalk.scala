package groksbeard.core

import java.nio.file.{Files, Path}
import java.util.Comparator

import scala.jdk.CollectionConverters.*

object NioSessionFs extends SessionFs:
  def listNames(dir: String): List[String] =
    val path = Path.of(dir)
    if !Files.isDirectory(path) then Nil
    else
      val stream = Files.list(path)
      try stream.iterator.asScala.map(_.getFileName.toString).toList
      finally stream.close()

  def isDirectory(path: String): Boolean =
    Files.isDirectory(Path.of(path))

  def mtimeMs(path: String): Option[Long] =
    val p = Path.of(path)
    if !Files.exists(p) then None
    else Some(Files.getLastModifiedTime(p).toMillis)

  def readText(path: String): Option[String] =
    val p = Path.of(path)
    if !Files.isRegularFile(p) then None
    else Some(Files.readString(p))

  override def writeText(path: String, text: String): Unit =
    val p = Path.of(path)
    Option(p.getParent).foreach(parent => Files.createDirectories(parent))
    Files.writeString(p, text)

  override def deleteTree(path: String): Unit =
    val p = Path.of(path)
    if Files.exists(p) then
      val walk = Files.walk(p)
      try walk.sorted(Comparator.reverseOrder()).forEach(Files.delete)
      finally walk.close()
end NioSessionFs

object SessionWalk:
  def fromDisk(home: String, cwd: String, limit: Int = SessionIndex.PageSize): List[SessionRow] =
    SessionIndex.listRows(NioSessionFs, home, cwd, limit)
