package groksbeard.core

import java.nio.charset.StandardCharsets

final case class SessionActivity(
    id: String,
    updatesMtimeMs: Option[Long] = None,
    eventsMtimeMs: Option[Long] = None,
    summaryMtimeMs: Option[Long] = None,
)

final case class SessionRow(
    id: String,
    title: String,
    activityMs: Long = 0,
    summary: Option[String] = None,
    lastTurn: Option[String] = None,
    modelId: Option[String] = None,
    messages: Option[Int] = None,
) derives zio.json.JsonCodec

trait SessionFs:
  def listNames(dir: String): List[String]
  def isDirectory(path: String): Boolean
  def mtimeMs(path: String): Option[Long]
  def readText(path: String): Option[String]

object SessionIndex:
  val PageSize: Int              = 100
  val WelcomeLimit: Int          = 8
  val MaxEncodedCwdBytes: Int    = 255
  val EmptyGraceMs: Long         = 10_000L
  val ContentSearchMinChars: Int = 2

  def join(dir: String, name: String): String =
    s"${dir.replaceAll("[\\\\/]+$", "")}/$name"

  def sessionsRoot(home: String): String =
    join(home, "sessions")

  /** Grok groups sessions as `sessions/<encodeURIComponent(cwd)>/`. Slash becomes `%2F`. */
  def encodeCwd(cwd: String): String =
    val bytes = cwd.getBytes(StandardCharsets.UTF_8)
    val sb    = new StringBuilder(bytes.length * 3)
    var i     = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      val c = b.toChar
      if isUnreserved(c) then sb.append(c)
      else sb.append(percentEncode(b))
      i += 1
    sb.toString
  end encodeCwd

  def encodedCwdExceedsLimit(encoded: String): Boolean =
    Utf8.byteLength(encoded) > MaxEncodedCwdBytes

  def sessionPath(home: String, cwd: String, sessionId: String): String =
    join(join(sessionsRoot(home), encodeCwd(cwd)), sessionId)

  def activityMs(stat: SessionActivity): Long =
    stat.updatesMtimeMs.orElse(stat.eventsMtimeMs).orElse(stat.summaryMtimeMs).getOrElse(0L)

  def byLastUsed(rows: List[SessionRow]): List[SessionRow] =
    rows.sortBy(r => -r.activityMs)

  /** Keep `ids` order; drop missing ids; append rows that are not in `ids` by last used. */
  def holdOrder(rows: List[SessionRow], ids: List[String]): List[SessionRow] =
    val byId  = rows.map(r => r.id -> r).toMap
    val held  = ids.flatMap(byId.get)
    val extra = byLastUsed(rows.filterNot(r => ids.contains(r.id)))
    held ++ extra

  def present(rows: List[SessionRow], order: Option[List[String]]): List[SessionRow] =
    order match
      case None      => byLastUsed(rows)
      case Some(ids) => holdOrder(rows, ids)

  /** TUI `/resume` (`dedup_empty_sessions`): after newest-first sort, keep one empty session (`num_messages == 0`) per
    * list. Older unused drafts drop so the page is not a pile of Untitled rows.
    */
  def isEmpty(row: SessionRow): Boolean =
    row.messages.contains(0)

  def dedupEmpty(rows: List[SessionRow]): List[SessionRow] =
    val (kept, _) =
      rows.foldLeft((Vector.empty[SessionRow], false)) { case ((acc, seenEmpty), row) =>
        if !isEmpty(row) then (acc :+ row, seenEmpty)
        else if seenEmpty then (acc, true)
        else (acc :+ row, true)
      }
    kept.toList

  def touchCurrent(rows: List[SessionRow], currentId: String, nowMs: Long, skip: Boolean): List[SessionRow] =
    byLastUsed(
      if currentId.isEmpty || skip then rows
      else
        rows.map { row =>
          if row.id == currentId then row.copy(activityMs = math.max(row.activityMs, nowMs))
          else row
        }
    )

  def index(stats: List[SessionActivity]): List[SessionActivity] =
    stats.sortBy(s => -activityMs(s))

  def page(ordered: List[SessionActivity], offset: Int, limit: Int = PageSize): List[String] =
    ordered.slice(offset, offset + limit).map(_.id)

  def matches(row: SessionRow, query: String): Boolean =
    val q = query.trim.toLowerCase
    if q.isEmpty then true
    else
      List(Some(row.id), Some(row.title), row.summary, row.lastTurn, row.modelId).flatten
        .exists(_.toLowerCase.contains(q))

  def filter(rows: List[SessionRow], query: String): List[SessionRow] =
    rows.filter(matches(_, query))

  private val OpaqueId =
    raw"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".r

  def isOpaqueId(title: String, id: String = ""): Boolean =
    val t = title.trim
    t.isEmpty || t == id || OpaqueId.matches(t)

  def displayTitle(row: SessionRow): String =
    val named = row.title.trim
    if named.nonEmpty && !isOpaqueId(named, row.id) then named
    else
      row.lastTurn
        .orElse(row.summary)
        .map(_.trim)
        .filter(t => t.nonEmpty && !isOpaqueId(t, row.id))
        .getOrElse("Untitled session")

  def groupDirs(fs: SessionFs, home: String, cwd: String): List[String] =
    val root    = sessionsRoot(home)
    val encoded = encodeCwd(cwd)
    val direct  = join(root, encoded)
    val fromCwd =
      fs.listNames(root).flatMap { name =>
        val dir = join(root, name)
        if fs.isDirectory(dir) && fs.readText(join(dir, ".cwd")).exists(_.trim == cwd) then Some(dir)
        else None
      }
    (direct +: fromCwd).distinct.filter(fs.isDirectory)
  end groupDirs

  def listRows(fs: SessionFs, home: String, cwd: String, limit: Int = PageSize): List[SessionRow] =
    val rows = groupDirs(fs, home, cwd).flatMap { group =>
      fs.listNames(group).flatMap { id =>
        val dir = join(group, id)
        if !fs.isDirectory(dir) then None
        else
          val summary = fs.readText(join(dir, "summary.json")).flatMap(SessionSummary.decode)
          val convo   =
            fs.mtimeMs(join(dir, "updates.jsonl")).orElse(fs.mtimeMs(join(dir, "events.jsonl")))
          val fallback = fs.mtimeMs(join(dir, "summary.json")).getOrElse(0L)
          val activity = SessionSummary.activityMs(summary, convo, fallback)
          Some(SessionSummary.row(id, activity, summary))
      }
    }
    byLastUsed(dedupEmpty(rows)).take(limit)
  end listRows

  private def isUnreserved(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')'

  private val Hex = "0123456789ABCDEF"

  private def percentEncode(b: Int): String =
    s"%${Hex.charAt((b >> 4) & 0xf)}${Hex.charAt(b & 0xf)}"
end SessionIndex
