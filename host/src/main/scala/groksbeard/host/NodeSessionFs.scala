package groksbeard.host

import groksbeard.core.BeardError
import groksbeard.core.BeardError.orSystem
import groksbeard.core.SessionFs
import zio.*

import scala.scalajs.js

object NodeSessionFs extends SessionFs:
  def listNames(dir: String): BeardError.Result[List[String]] =
    ZIO.attempt {
      if nodeFs.existsSync(dir) && nodeFs.statSync(dir).isDirectory() then nodeFs.readdirSync(dir).toList
      else Nil
    }.orSystem

  def isDirectory(path: String): BeardError.Result[Boolean] =
    ZIO.attempt(nodeFs.existsSync(path) && nodeFs.statSync(path).isDirectory()).orSystem

  def mtimeMs(path: String): BeardError.Result[Option[Long]] =
    ZIO.attempt {
      if nodeFs.existsSync(path) then Some(nodeFs.statSync(path).mtimeMs.toLong) else None
    }.orSystem

  def readText(path: String): BeardError.Result[Option[String]] =
    ZIO.attempt {
      if nodeFs.existsSync(path) then Some(nodeFs.readFileSync(path, "utf8")) else None
    }.orSystem

  override def writeText(path: String, text: String): BeardError.Result[Unit] =
    ZIO.attempt {
      nodeFs.mkdirSync(nodePath.dirname(path), js.Dynamic.literal(recursive = true))
      nodeFs.writeFileSync(path, text, "utf8")
      ()
    }.orSystem

  override def deleteTree(path: String): BeardError.Result[Unit] =
    ZIO.attempt(nodeFs.rmSync(path, js.Dynamic.literal(recursive = true, force = true))).orSystem
end NodeSessionFs
