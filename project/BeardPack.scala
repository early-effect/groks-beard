import _root_.sbt.*

/** Stamp the VSIX marketplace version into root `package.json`. vsce reads that field. */
object BeardPack:
  def stampPackageJson(base: File, version: String): Unit =
    val pkg     = base / "package.json"
    val raw     = IO.read(pkg)
    val stamped = raw.replaceFirst("""("version"\s*:\s*")[^"]+(")""", "$1" + version + "$2")
    if !stamped.contains("\"version\": \"" + version + "\"") then
      sys.error(s"could not write version $version into ${pkg.getPath}")
    if stamped != raw then IO.write(pkg, stamped)

  def writeProductVersion(sourceManaged: File, version: String): File =
    val file = sourceManaged / "groksbeard" / "core" / "ProductVersion.scala"
    IO.write(
      file,
      s"""package groksbeard.core
         |
         |object ProductVersion:
         |  val current: String = "$version"
         |""".stripMargin,
    )
    file

  def stageDist(dest: File, hostJs: File, chatJs: File, mcpJs: File): File =
    val webview = dest / "webview"
    IO.createDirectory(webview)
    IO.copyFile(hostJs, dest / "extension.js")
    IO.copyFile(chatJs, webview / "chat.js")
    IO.copyFile(mcpJs, dest / "mcp-proxy.js")
    dest
