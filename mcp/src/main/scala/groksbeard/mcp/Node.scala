package groksbeard.mcp

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait Socket extends js.Object:
  def write(data: String): Boolean                                     = js.native
  def end(): Unit                                                      = js.native
  def destroy(): Unit                                                  = js.native
  def setEncoding(enc: String): Socket                                 = js.native
  def setTimeout(ms: Int, cb: js.Function0[Any]): Socket               = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): Socket   = js.native
  def once(event: String, listener: js.Function1[js.Any, Any]): Socket = js.native
  def off(event: String, listener: js.Function1[js.Any, Any]): Socket  = js.native
  def destroyed: Boolean                                               = js.native
end Socket

@js.native
trait Server extends js.Object:
  def listen(path: String, cb: js.Function0[Any]): Server             = js.native
  def close(cb: js.UndefOr[js.Function0[Any]] = js.undefined): Server = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): Server  = js.native

@js.native
@JSImport("net", JSImport.Namespace)
object net extends js.Object:
  def createServer(listener: js.Function1[Socket, Any]): Server = js.native
  def connect(path: String): Socket                             = js.native

@js.native
trait NodeFsApi extends js.Object:
  def mkdirSync(path: String, options: js.Dynamic): Unit           = js.native
  def unlinkSync(path: String): Unit                               = js.native
  def chmodSync(path: String, mode: Int): Unit                     = js.native
  def existsSync(path: String): Boolean                            = js.native
  def readFileSync(path: String, enc: String): String              = js.native
  def writeFileSync(path: String, data: String, enc: String): Unit = js.native

@js.native
@JSImport("fs", JSImport.Namespace)
object fs extends NodeFsApi

@js.native
trait Hash extends js.Object:
  def update(data: String): Hash       = js.native
  def digest(encoding: String): String = js.native

@js.native
@JSImport("crypto", JSImport.Namespace)
object crypto extends js.Object:
  def createHash(algorithm: String): Hash = js.native

@js.native
trait NodeProcessApi extends js.Object:
  def argv: js.Array[String]                 = js.native
  def stdin: Socket                          = js.native
  def stdout: Socket                         = js.native
  def stderr: Socket                         = js.native
  def env: js.Dictionary[js.UndefOr[String]] = js.native
  def platform: String                       = js.native
  def exit(code: Int): Nothing               = js.native
  def cwd(): String                          = js.native
end NodeProcessApi

@js.native
@JSImport("process", JSImport.Namespace)
object process extends NodeProcessApi

@js.native
@JSImport("os", JSImport.Namespace)
object os extends js.Object:
  def tmpdir(): String  = js.native
  def homedir(): String = js.native

@js.native
@JSImport("path", JSImport.Namespace)
object path extends js.Object:
  def dirname(p: String): String   = js.native
  def join(parts: String*): String = js.native
