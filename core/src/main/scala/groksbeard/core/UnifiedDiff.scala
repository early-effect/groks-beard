package groksbeard.core

enum DiffLine:
  case Context(text: String)
  case Add(text: String)
  case Del(text: String)

object UnifiedDiff:
  def lines(oldText: String, newText: String): List[DiffLine] =
    val a = oldText.split("\n", -1).toList
    val b = newText.split("\n", -1).toList
    walk(a, b, lcsTable(a, b))

  def fileName(path: String): String =
    val norm = path.replace('\\', '/')
    val i    = norm.lastIndexOf('/')
    if i < 0 then path else norm.substring(i + 1)

  private def lcsTable(a: List[String], b: List[String]): Array[Array[Int]] =
    val n = a.length
    val m = b.length
    val t = Array.fill(n + 1, m + 1)(0)
    var i = n - 1
    while i >= 0 do
      var j = m - 1
      while j >= 0 do
        t(i)(j) = if a(i) == b(j) then t(i + 1)(j + 1) + 1 else math.max(t(i + 1)(j), t(i)(j + 1))
        j -= 1
      i -= 1
    t
  end lcsTable

  private def walk(a: List[String], b: List[String], t: Array[Array[Int]]): List[DiffLine] =
    val out = List.newBuilder[DiffLine]
    var i   = 0
    var j   = 0
    val n   = a.length
    val m   = b.length
    while i < n && j < m do
      if a(i) == b(j) then
        out += DiffLine.Context(a(i))
        i += 1
        j += 1
      else if t(i + 1)(j) >= t(i)(j + 1) then
        out += DiffLine.Del(a(i))
        i += 1
      else
        out += DiffLine.Add(b(j))
        j += 1
    end while
    while i < n do
      out += DiffLine.Del(a(i))
      i += 1
    while j < m do
      out += DiffLine.Add(b(j))
      j += 1
    val rows = out.result()
    dropTrailingEmpty(rows)
  end walk

  private def dropTrailingEmpty(rows: List[DiffLine]): List[DiffLine] =
    rows match
      case init :+ DiffLine.Context("") if init.nonEmpty => init
      case other                                         => other
end UnifiedDiff
