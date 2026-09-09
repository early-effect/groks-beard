package groksbeard.core

object VimNav:
  enum Motion:
    case Prev, Next, Top, Bottom, Yank, Fold, Collapse, Expand

  def motion(key: String, shift: Boolean): Option[Motion] =
    (key, shift) match
      case ("j", false) | ("ArrowDown", false)  => Some(Motion.Next)
      case ("k", false) | ("ArrowUp", false)    => Some(Motion.Prev)
      case ("g", false)                         => Some(Motion.Top)
      case ("G", true) | ("g", true)            => Some(Motion.Bottom)
      case ("y", false)                         => Some(Motion.Yank)
      case ("e", false)                         => Some(Motion.Fold)
      case ("h", false) | ("ArrowLeft", false)  => Some(Motion.Collapse)
      case ("l", false) | ("ArrowRight", false) => Some(Motion.Expand)
      case _                                    => None

  def step(index: Int, size: Int, motion: Motion): Int =
    if size <= 0 then 0
    else
      motion match
        case Motion.Prev                                                 => math.max(0, index - 1)
        case Motion.Next                                                 => math.min(size - 1, index + 1)
        case Motion.Top                                                  => 0
        case Motion.Bottom                                               => size - 1
        case Motion.Yank | Motion.Fold | Motion.Collapse | Motion.Expand => index
end VimNav
