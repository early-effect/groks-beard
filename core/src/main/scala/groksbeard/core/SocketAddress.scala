package groksbeard.core

object SocketAddress:
  val SocketMode: Int    = Integer.parseInt("600", 8)
  val SocketDirMode: Int = Integer.parseInt("700", 8)

  def normalize(workspace: String, win: Boolean, realpath: String => String = identity): String =
    val resolved = realpath(workspace)
    if win then resolved.toLowerCase else resolved

  def hash16(normalized: String, sha256Hex: String => String): String =
    sha256Hex(normalized).take(16)

  def address(
      workspace: String,
      win: Boolean,
      sha256Hex: String => String,
      runtimeDir: String,
      realpath: String => String = identity,
  ): String =
    val hash = hash16(normalize(workspace, win, realpath), sha256Hex)
    if win then s"\\\\.\\pipe\\groks-beard-$hash"
    else
      val root = runtimeDir.stripSuffix("/").stripSuffix("\\")
      s"$root/groks-beard/$hash.sock"
  end address
end SocketAddress
