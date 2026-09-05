package groksbeard.host

import groksbeard.core.SessionFs

import scala.scalajs.js

object NodeSessionFs extends SessionFs:
  def listNames(dir: String): List[String] =
    try nodeFs.readdirSync(dir).toList
    catch case _: Throwable => Nil

  def isDirectory(path: String): Boolean =
    try nodeFs.existsSync(path) && nodeFs.statSync(path).isDirectory()
    catch case _: Throwable => false

  def mtimeMs(path: String): Option[Long] =
    try if nodeFs.existsSync(path) then Some(nodeFs.statSync(path).mtimeMs.toLong) else None
    catch case _: Throwable => None

  def readText(path: String): Option[String] =
    try if nodeFs.existsSync(path) then Some(nodeFs.readFileSync(path, "utf8")) else None
    catch case _: Throwable => None

  override def writeText(path: String, text: String): Unit =
    try
      nodeFs.mkdirSync(nodePath.dirname(path), js.Dynamic.literal(recursive = true))
      nodeFs.writeFileSync(path, text, "utf8")
    catch case _: Throwable => ()

  override def deleteTree(path: String): Unit =
    try nodeFs.rmSync(path, js.Dynamic.literal(recursive = true, force = true))
    catch case _: Throwable => ()
end NodeSessionFs
