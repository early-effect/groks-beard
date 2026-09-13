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
  def rename(id: SessionId, op: RenameOp): BeardError.Result[Option[SessionRow]]
  def delete(id: SessionId): BeardError.Result[Boolean]
  def scheduleEmptyDelete(id: SessionId): UIO[Unit]
  def plan(id: SessionId): BeardError.Result[List[TodoEntry]]
  def planMarkdown(id: SessionId): BeardError.Result[String]
  def writeWorkspacePlan(markdown: String): BeardError.Result[String]
  def agents: BeardError.Result[List[AgentDef]]
  def personas: BeardError.Result[List[PersonaDef]]
  def readConfig: BeardError.Result[String]
  def writeConfig(text: String): BeardError.Result[Unit]
end SessionRepo

object SessionRepo:
  def of(fs: SessionFs, home: String, cwd: String): ULayer[SessionRepo] =
    ZLayer.succeed(new SessionRepo:
      def list: BeardError.Result[List[SessionRow]] =
        SessionIndex.listRows(fs, home, cwd)

      def rename(id: SessionId, op: RenameOp): BeardError.Result[Option[SessionRow]] =
        SessionEdit.rename(fs, home, cwd, id, op)

      def delete(id: SessionId): BeardError.Result[Boolean] =
        SessionEdit.delete(fs, home, cwd, id)

      def scheduleEmptyDelete(id: SessionId): UIO[Unit] =
        val path = SessionIndex.sessionPath(home, cwd, id)
        (ZIO.sleep(SessionIndex.EmptyGraceMs.millis) *>
          fs.deleteTree(path).tapError(e => ZIO.logWarning(e.message)).ignore).forkDaemon.unit

      def plan(id: SessionId): BeardError.Result[List[TodoEntry]] =
        SessionIndex.readPlan(fs, home, cwd, id)

      def planMarkdown(id: SessionId): BeardError.Result[String] =
        SessionIndex.readPlanMarkdown(fs, home, cwd, id)

      def writeWorkspacePlan(markdown: String): BeardError.Result[String] =
        val path = SessionIndex.workspacePlanPath(cwd)
        fs.writeText(path, markdown).as(path)

      def agents: BeardError.Result[List[AgentDef]] =
        val user = SessionIndex.join(home, "agents")
        val proj = SessionIndex.join(SessionIndex.join(cwd, ".grok"), "agents")
        SessionIndex.listMarkdownAgents(fs, proj).flatMap { p =>
          SessionIndex.listMarkdownAgents(fs, user).map(u => AgentsCatalog.merge(p ++ u))
        }

      def personas: BeardError.Result[List[PersonaDef]] =
        val user = SessionIndex.join(home, "personas")
        val proj = SessionIndex.join(SessionIndex.join(cwd, ".grok"), "personas")
        SessionIndex.listTomlPersonas(fs, proj).flatMap { p =>
          SessionIndex.listTomlPersonas(fs, user).map(u => p ++ u)
        }

      def readConfig: BeardError.Result[String] =
        fs.readText(ConfigToml.userPath(home)).map(_.getOrElse(""))

      def writeConfig(text: String): BeardError.Result[Unit] =
        fs.writeText(ConfigToml.userPath(home), text))

  def test(
      listRows: () => List[SessionRow] = () => Nil,
      onRename: (SessionId, RenameOp) => Option[SessionRow] = (_, _) => None,
      onDelete: SessionId => Boolean = _ => false,
      onEmptyDelete: SessionId => Unit = _ => (),
      onPlan: SessionId => List[TodoEntry] = _ => Nil,
      onPlanMarkdown: SessionId => String = _ => "",
      onWriteWorkspacePlan: String => String = _ => SessionIndex.workspacePlanPath("."),
      onAgents: () => List[AgentDef] = () => AgentsCatalog.builtins,
      onPersonas: () => List[PersonaDef] = () => Nil,
      onReadConfig: () => String = () => "",
      onWriteConfig: String => Unit = _ => (),
  ): ULayer[SessionRepo] =
    ZLayer.succeed(new SessionRepo:
      def list: BeardError.Result[List[SessionRow]]                                  = ZIO.succeed(listRows())
      def rename(id: SessionId, op: RenameOp): BeardError.Result[Option[SessionRow]] =
        ZIO.succeed(onRename(id, op))
      def delete(id: SessionId): BeardError.Result[Boolean]               = ZIO.succeed(onDelete(id))
      def scheduleEmptyDelete(id: SessionId): UIO[Unit]                   = ZIO.succeed(onEmptyDelete(id))
      def plan(id: SessionId): BeardError.Result[List[TodoEntry]]         = ZIO.succeed(onPlan(id))
      def planMarkdown(id: SessionId): BeardError.Result[String]          = ZIO.succeed(onPlanMarkdown(id))
      def writeWorkspacePlan(markdown: String): BeardError.Result[String] =
        ZIO.succeed(onWriteWorkspacePlan(markdown))
      def agents: BeardError.Result[List[AgentDef]]          = ZIO.succeed(onAgents())
      def personas: BeardError.Result[List[PersonaDef]]      = ZIO.succeed(onPersonas())
      def readConfig: BeardError.Result[String]              = ZIO.succeed(onReadConfig())
      def writeConfig(text: String): BeardError.Result[Unit] = ZIO.succeed(onWriteConfig(text)))
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
  def openText(path: String): UIO[Unit]

object ReviewOps:
  def layer(
      read: String => BeardError.Result[Option[String]] = _ => ZIO.none,
      openDiffs: (String, List[DiffPair]) => UIO[Unit] = (_, _) => ZIO.unit,
      undo: List[UndoMutation] => BeardError.Result[Unit] = _ => ZIO.unit,
      dirty: String => UIO[Boolean] = _ => ZIO.succeed(true),
      storeChanged: UIO[Unit] = ZIO.unit,
      onFollow: (String, Option[Int]) => UIO[Unit] = (_, _) => ZIO.unit,
      onOpenText: String => UIO[Unit] = _ => ZIO.unit,
  ): ULayer[ReviewOps] =
    ZLayer.succeed(new ReviewOps:
      def readDisk(path: String): BeardError.Result[Option[String]]          = read(path)
      def openNativeDiffs(heading: String, diffs: List[DiffPair]): UIO[Unit] = openDiffs(heading, diffs)
      def applyUndo(mutations: List[UndoMutation]): BeardError.Result[Unit]  = undo(mutations)
      def confirmDirty(path: String): UIO[Boolean]                           = dirty(path)
      def onStoreChange: UIO[Unit]                                           = storeChanged
      def follow(path: String, line: Option[Int]): UIO[Unit]                 = onFollow(path, line)
      def openText(path: String): UIO[Unit]                                  = onOpenText(path))

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
  type Env = HostOut & SessionRepo & Mentions & ChangesPersist & ReviewOps & TranscriptOut & Terminals & Mcps & UiPrefs

  def test(
      post: HostMsg => UIO[Unit] = _ => ZIO.unit,
      searchFiles: String => List[MentionFile] = _ => Nil,
      listSessions: () => List[SessionRow] = () => Nil,
      scheduleEmptyDelete: SessionId => Unit = _ => (),
      renameOnDisk: (SessionId, RenameOp) => Option[SessionRow] = (_, _) => None,
      deleteOnDisk: SessionId => Boolean = _ => false,
      planOnDisk: SessionId => List[TodoEntry] = _ => Nil,
      persistChanges: List[ChangeSet] => UIO[Unit] = _ => ZIO.unit,
      readDisk: String => Option[String] = _ => None,
      openNativeDiffs: (String, List[DiffPair]) => Unit = (_, _) => (),
      applyUndo: List[UndoMutation] => Unit = _ => (),
      confirmDirty: String => Boolean = _ => true,
      onStoreChange: () => Unit = () => (),
      followFile: (String, Option[Int]) => Unit = (_, _) => (),
      openText: String => Unit = _ => (),
      planMarkdownOnDisk: SessionId => String = _ => "",
      writeWorkspacePlan: String => String = _ => SessionIndex.workspacePlanPath("."),
      onCopy: (String, Option[String], Boolean, Boolean) => CopyResult = (text, path, _, conversation) =>
        CopyResult(TranscriptCopy.toast(path, conversation), if path.isEmpty then Some(text) else None),
      onReadConfig: () => String = () => "",
      onWriteConfig: String => Unit = _ => (),
      terminals: ULayer[Terminals] = Terminals.test(),
      mcps: ULayer[Mcps] = Mcps.none,
  ): ULayer[Env] =
    val base =
      HostOut.layer(post) ++
        SessionRepo.test(
          listSessions,
          renameOnDisk,
          deleteOnDisk,
          scheduleEmptyDelete,
          planOnDisk,
          planMarkdownOnDisk,
          writeWorkspacePlan,
          onReadConfig = onReadConfig,
          onWriteConfig = onWriteConfig,
        ) ++
        Mentions.layer(q => ZIO.succeed(searchFiles(q))) ++
        ChangesPersist.layer(sets => persistChanges(sets)) ++
        ReviewOps.layer(
          read = p => ZIO.succeed(readDisk(p)),
          openDiffs = (h, d) => ZIO.succeed(openNativeDiffs(h, d)),
          undo = m => ZIO.succeed(applyUndo(m)),
          dirty = p => ZIO.succeed(confirmDirty(p)),
          storeChanged = ZIO.succeed(onStoreChange()),
          onFollow = (p, l) => ZIO.succeed(followFile(p, l)),
          onOpenText = p => ZIO.succeed(openText(p)),
        ) ++
        TranscriptOut.test(onCopy) ++
        terminals ++
        mcps
    base >+> UiPrefs.layer
  end test
end ChatEnv
