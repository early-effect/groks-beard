package groksbeard.host

import groksbeard.core.*
import groksbeard.host.vscode.*
import zio.*
import zio.json.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

final class ChangeDisk(context: ExtensionContext):
  private var written: Set[String] = Set.empty
  private val gate                 = new java.util.concurrent.atomic.AtomicInteger(0)

  def load: BeardError.Result[List[ChangeSet]] =
    val raw = context.workspaceState.get[String](ChangePersist.IndexKey).toOption.getOrElse("")
    if raw.isEmpty then ZIO.succeed(Nil)
    else
      ZIO
        .fromEither(raw.fromJson[List[ChangeIndexSet]].left.map(BeardError.decode))
        .flatMap { rows =>
          val keys = rows.flatMap { s =>
            s.files.flatMap { f =>
              List(
                Option.when(f.hasOld)(ChangePersist.rel(s.sessionId, s.turnId, f.path, "old")),
                Option.when(f.hasNew)(ChangePersist.rel(s.sessionId, s.turnId, f.path, "new")),
              ).flatten
            }
          }
          ZIO.foreach(keys)(k => readRel(k).map(k -> _)).map { pairs =>
            val snaps = pairs.toMap
            val sets  = ChangePersist.hydrate(rows, k => snaps.get(k).flatten)
            written = ChangePersist.rels(sets).toSet
            sets
          }
        }
    end if
  end load

  def save(sets: List[ChangeSet]): BeardError.Result[Unit] =
    val ticket = gate.incrementAndGet()
    val next   = ChangePersist.rels(sets).toSet
    val stale  = written -- next
    val bodies = ChangePersist.bodies(sets)
    val index  = ChangePersist.index(sets).toJson
    for
      _ <- ChangeDisk.fromPromise(context.workspaceState.update(ChangePersist.IndexKey, index))
      _ <- context.storageUri.toOption match
        case None       => ZIO.unit
        case Some(root) =>
          ZIO.foreachDiscard(stale)(rel => deleteRel(root, rel)) *>
            ZIO.foreachDiscard(bodies) { (rel, text) => writeRel(root, rel, text) }
      _ <- ZIO.succeed {
        if gate.get() == ticket then written = next
      }
    yield ()
    end for
  end save

  private def joinRel(root: Uri, rel: String): Uri =
    vscode.Uri.joinPath(root, rel.split('/').toList*)

  private def writeRel(root: Uri, rel: String, text: String): BeardError.Result[Unit] =
    val uri  = joinRel(root, rel)
    val dir  = vscode.Uri.joinPath(uri, "..")
    val data = Review.utf8(text)
    ChangeDisk.fromPromise(vscode.workspace.fs.createDirectory(dir)) *>
      ChangeDisk.fromPromise(vscode.workspace.fs.writeFile(uri, data))

  private def readRel(rel: String): BeardError.Result[Option[String]] =
    context.storageUri.toOption match
      case None       => ZIO.none
      case Some(root) =>
        ChangeDisk
          .fromPromise(vscode.workspace.fs.readFile(joinRel(root, rel)))
          .map(bytes => Some(ChangeDisk.utf8String(bytes)))
          .orElseSucceed(None)

  private def deleteRel(root: Uri, rel: String): BeardError.Result[Unit] =
    ChangeDisk.fromPromise(vscode.workspace.fs.delete(joinRel(root, rel))).orElseSucceed(())
end ChangeDisk

object ChangeDisk:
  def utf8String(bytes: Uint8Array): String =
    new groksbeard.facade.TextDecoder("utf-8").decode(bytes)

  def fromPromise[A](make: => js.Promise[A]): BeardError.Result[A] =
    ZIO.async[Any, BeardError, A] { cb =>
      val p =
        try Some(make)
        catch
          case t: Throwable =>
            cb(ZIO.fail(BeardError.system(t)))
            None
      p.foreach { promise =>
        val _ = promise.`then`(
          (a: A) =>
            cb(ZIO.succeed(a)); (): js.Any
          ,
          (e: Any) =>
            val t = e match
              case err: Throwable => err
              case other          => new RuntimeException(String.valueOf(other))
            cb(ZIO.fail(BeardError.system(t)))
            (): js.Any,
        )
      }
      ()
    }
end ChangeDisk
