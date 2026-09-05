package groksbeard.core

final case class DiffPair(path: String, oldText: String, newText: String, wholeFile: Boolean = true)
