package groksbeard.facade

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
trait FileList extends js.Object:
  def length: Int              = js.native
  def item(index: Int): js.Any = js.native

@js.native
trait DataTransferItemList extends js.Object:
  def length: Int                               = js.native
  def add(data: js.Any): js.Any                 = js.native
  def add(data: String, `type`: String): js.Any = js.native

@js.native
@JSGlobal
class DataTransfer() extends js.Object:
  var dropEffect: String                          = js.native
  var effectAllowed: String                       = js.native
  def items: DataTransferItemList                 = js.native
  def files: FileList                             = js.native
  def setData(format: String, data: String): Unit = js.native
  def getData(format: String): String             = js.native

class FilePropertyBag(val `type`: String) extends js.Object

@js.native
@JSGlobal
class File(
    @unused bits: js.Array[js.Any],
    @unused fileName: String,
    @unused options: js.UndefOr[FilePropertyBag] = js.undefined,
) extends js.Object:
  def name: String   = js.native
  def `type`: String = js.native
  def size: Double   = js.native
end File

@js.native
@JSGlobal
class FileReader() extends js.Object:
  def result: js.Any                      = js.native
  var onload: js.Function1[js.Any, Unit]  = js.native
  var onerror: js.Function1[js.Any, Unit] = js.native
  def readAsDataURL(blob: js.Any): Unit   = js.native
  def abort(): Unit                       = js.native

class ClipboardEventInit(
    val clipboardData: DataTransfer,
    val bubbles: Boolean = true,
    val cancelable: Boolean = true,
) extends js.Object

@js.native
@JSGlobal
class ClipboardEvent(@unused `type`: String, @unused init: ClipboardEventInit) extends js.Object:
  def clipboardData: DataTransfer = js.native
  def preventDefault(): Unit      = js.native
