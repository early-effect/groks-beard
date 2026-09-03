package groksbeard.core

object ToolView:
  val Tail: Int = 4
  val Clip: Int = 1600

  def splitTail[A](tools: List[A], tail: Int = Tail): (List[A], List[A]) =
    if tools.length <= tail then (Nil, tools)
    else (tools.dropRight(tail), tools.takeRight(tail))

  def rollupLabel(count: Int): String =
    if count == 1 then "1 earlier tool" else s"$count earlier tools"

  def clip(text: String, limit: Int = Clip): String =
    if text.length <= limit then text else s"${text.take(limit)}\n…"

  def permissionTip(name: String, kind: String): String =
    kind match
      case "allow_once"    => s"$name: allow this once"
      case "allow_always"  => s"$name: allow this for the rest of the session"
      case "reject_once"   => s"$name: skip this once"
      case "reject_always" => s"$name: deny this for the rest of the session"
      case _               => name
end ToolView
