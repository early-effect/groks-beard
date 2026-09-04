package groksbeard.core

import zio.test.*

import java.nio.file.{Files, Path}

object PackageJsonSpec extends ZIOSpecDefault:
  def spec =
    suite("package.json")(
      test("contributes Add Selection, Add File, and the @-ref copy command") {
        val json = Files.readString(packageJson)
        assertTrue(
          json.contains("\"groksBeard.addSelection\""),
          json.contains("\"groksBeard.addFile\""),
          json.contains("\"groksBeard.copySelectionAsGrokRef\""),
          json.contains("ctrl+shift+;"),
          json.contains("includeActiveFileByDefault"),
          json.contains("useCtrlEnterToSend"),
          json.contains("editorHasSelection"),
        )
      }
    )

  private def packageJson: Path =
    val start = Path.of(sys.props.getOrElse("user.dir", ".")).toAbsolutePath.normalize
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .map { dir =>
        val nested = dir.resolve("beard").resolve("package.json")
        val here   = dir.resolve("package.json")
        if Files.isRegularFile(nested) then nested
        else if Files.isRegularFile(here) && Files.readString(here).contains("groks-beard") then here
        else null
      }
      .find(_ != null)
      .getOrElse(sys.error(s"beard/package.json not found from $start"))
  end packageJson
end PackageJsonSpec
