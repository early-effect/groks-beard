package groksbeard.core

import zio.*

trait HostOut:
  def post(msg: HostMsg): UIO[Unit]

object HostOut:
  def layer(run: HostMsg => UIO[Unit]): ULayer[HostOut] =
    ZLayer.succeed(new HostOut:
      def post(msg: HostMsg): UIO[Unit] = run(msg))

trait SessionRepo:
  def list: BeardError.Result[List[SessionRow]]
  def rename(id: String, op: RenameOp): BeardError.Result[Option[SessionRow]]
  def delete(id: String): BeardError.Result[Boolean]
  def scheduleEmptyDelete(id: String): UIO[Unit]

object SessionRepo:
  def of(fs: SessionFs, home: String, cwd: String): ULayer[SessionRepo] =
    ZLayer.succeed(new SessionRepo:
      def list: BeardError.Result[List[SessionRow]] =
        SessionIndex.listRows(fs, home, cwd)

      def rename(id: String, op: RenameOp): BeardError.Result[Option[SessionRow]] =
        SessionEdit.rename(fs, home, cwd, id, op)

      def delete(id: String): BeardError.Result[Boolean] =
        SessionEdit.delete(fs, home, cwd, id)

      def scheduleEmptyDelete(id: String): UIO[Unit] =
        val path = SessionIndex.sessionPath(home, cwd, id)
        (ZIO.sleep(SessionIndex.EmptyGraceMs.millis) *>
          fs.deleteTree(path).tapError(e => ZIO.logWarning(e.message)).ignore).forkDaemon.unit)

  def test(
      listRows: () => List[SessionRow] = () => Nil,
      onRename: (String, RenameOp) => Option[SessionRow] = (_, _) => None,
      onDelete: String => Boolean = _ => false,
      onEmptyDelete: String => Unit = _ => (),
  ): ULayer[SessionRepo] =
    ZLayer.succeed(new SessionRepo:
      def list: BeardError.Result[List[SessionRow]]                               = ZIO.succeed(listRows())
      def rename(id: String, op: RenameOp): BeardError.Result[Option[SessionRow]] =
        ZIO.succeed(onRename(id, op))
      def delete(id: String): BeardError.Result[Boolean] = ZIO.succeed(onDelete(id))
      def scheduleEmptyDelete(id: String): UIO[Unit]     = ZIO.succeed(onEmptyDelete(id)))
end SessionRepo

trait Mentions:
  def search(query: String): BeardError.Result[List[MentionFile]]

object Mentions:
  def layer(run: String => BeardError.Result[List[MentionFile]]): ULayer[Mentions] =
    ZLayer.succeed(new Mentions:
      def search(query: String): BeardError.Result[List[MentionFile]] = run(query))

  val none: ULayer[Mentions] = layer(_ => ZIO.succeed(Nil))

trait ChangesPersist:
  def save(sets: List[ChangeSet]): BeardError.Result[Unit]
  def load: BeardError.Result[List[ChangeSet]]

object ChangesPersist:
  def layer(
      saveSets: List[ChangeSet] => BeardError.Result[Unit],
      loadSets: BeardError.Result[List[ChangeSet]] = ZIO.succeed(Nil),
  ): ULayer[ChangesPersist] =
    ZLayer.succeed(new ChangesPersist:
      def save(sets: List[ChangeSet]): BeardError.Result[Unit] = saveSets(sets)
      def load: BeardError.Result[List[ChangeSet]]             = loadSets)

  val noop: ULayer[ChangesPersist] = layer(_ => ZIO.unit)
end ChangesPersist

trait ReviewOps:
  def readDisk(path: String): BeardError.Result[Option[String]]
  def openNativeDiffs(heading: String, diffs: List[DiffPair]): UIO[Unit]
  def applyUndo(mutations: List[UndoMutation]): BeardError.Result[Unit]
  def confirmDirty(path: String): UIO[Boolean]
  def onStoreChange: UIO[Unit]
  def follow(path: String, line: Option[Int]): UIO[Unit]

object ReviewOps:
  def layer(
      read: String => BeardError.Result[Option[String]] = _ => ZIO.none,
      openDiffs: (String, List[DiffPair]) => UIO[Unit] = (_, _) => ZIO.unit,
      undo: List[UndoMutation] => BeardError.Result[Unit] = _ => ZIO.unit,
      dirty: String => UIO[Boolean] = _ => ZIO.succeed(true),
      storeChanged: UIO[Unit] = ZIO.unit,
      onFollow: (String, Option[Int]) => UIO[Unit] = (_, _) => ZIO.unit,
  ): ULayer[ReviewOps] =
    ZLayer.succeed(new ReviewOps:
      def readDisk(path: String): BeardError.Result[Option[String]]          = read(path)
      def openNativeDiffs(heading: String, diffs: List[DiffPair]): UIO[Unit] = openDiffs(heading, diffs)
      def applyUndo(mutations: List[UndoMutation]): BeardError.Result[Unit]  = undo(mutations)
      def confirmDirty(path: String): UIO[Boolean]                           = dirty(path)
      def onStoreChange: UIO[Unit]                                           = storeChanged
      def follow(path: String, line: Option[Int]): UIO[Unit]                 = onFollow(path, line))

  val ignore: ULayer[ReviewOps] = layer()
end ReviewOps

trait TranscriptOut:
  def deliver(
      text: String,
      path: Option[String],
      backup: Boolean,
      conversation: Boolean,
  ): BeardError.Result[CopyResult]

object TranscriptOut:
  def of(
      fs: SessionFs,
      home: String,
      cwd: String,
      env: String => Option[String],
      clipboard: String => UIO[Unit] = _ => ZIO.unit,
  ): ULayer[TranscriptOut] =
    ZLayer.succeed(new TranscriptOut:
      def deliver(
          text: String,
          path: Option[String],
          backup: Boolean,
          conversation: Boolean,
      ): BeardError.Result[CopyResult] =
        val dest =
          path.map(_.trim).filter(_.nonEmpty).map(p => TranscriptCopy.expandPath(p, TranscriptCopy.userHome(env), cwd))
        val bak   = if backup then Some(TranscriptCopy.backupPath(env, home, cwd)) else None
        val files = List(dest, bak).flatten.distinct
        ZIO.foreachDiscard(files)(p => fs.writeText(p, text)) *>
          (if dest.isEmpty then clipboard(text) else ZIO.unit).as(
            CopyResult(TranscriptCopy.toast(dest, conversation), if dest.isEmpty then Some(text) else None)
          ))

  def test(
      onDeliver: (String, Option[String], Boolean, Boolean) => CopyResult = (text, path, _, conversation) =>
        CopyResult(TranscriptCopy.toast(path, conversation), if path.isEmpty then Some(text) else None)
  ): ULayer[TranscriptOut] =
    ZLayer.succeed(new TranscriptOut:
      def deliver(
          text: String,
          path: Option[String],
          backup: Boolean,
          conversation: Boolean,
      ): BeardError.Result[CopyResult] =
        ZIO.succeed(onDeliver(text, path, backup, conversation)))
end TranscriptOut

object ChatEnv:
  type Env = HostOut & SessionRepo & Mentions & ChangesPersist & ReviewOps & TranscriptOut

  def test(
      post: HostMsg => UIO[Unit] = _ => ZIO.unit,
      searchFiles: String => List[MentionFile] = _ => Nil,
      listSessions: () => List[SessionRow] = () => Nil,
      scheduleEmptyDelete: String => Unit = _ => (),
      renameOnDisk: (String, RenameOp) => Option[SessionRow] = (_, _) => None,
      deleteOnDisk: String => Boolean = _ => false,
      persistChanges: List[ChangeSet] => UIO[Unit] = _ => ZIO.unit,
      readDisk: String => Option[String] = _ => None,
      openNativeDiffs: (String, List[DiffPair]) => Unit = (_, _) => (),
      applyUndo: List[UndoMutation] => Unit = _ => (),
      confirmDirty: String => Boolean = _ => true,
      onStoreChange: () => Unit = () => (),
      followFile: (String, Option[Int]) => Unit = (_, _) => (),
      onCopy: (String, Option[String], Boolean, Boolean) => CopyResult = (text, path, _, conversation) =>
        CopyResult(TranscriptCopy.toast(path, conversation), if path.isEmpty then Some(text) else None),
  ): ULayer[Env] =
    HostOut.layer(post) ++
      SessionRepo.test(listSessions, renameOnDisk, deleteOnDisk, scheduleEmptyDelete) ++
      Mentions.layer(q => ZIO.succeed(searchFiles(q))) ++
      ChangesPersist.layer(sets => persistChanges(sets)) ++
      ReviewOps.layer(
        read = p => ZIO.succeed(readDisk(p)),
        openDiffs = (h, d) => ZIO.succeed(openNativeDiffs(h, d)),
        undo = m => ZIO.succeed(applyUndo(m)),
        dirty = p => ZIO.succeed(confirmDirty(p)),
        storeChanged = ZIO.succeed(onStoreChange()),
        onFollow = (p, l) => ZIO.succeed(followFile(p, l)),
      ) ++
      TranscriptOut.test(onCopy)
end ChatEnv
