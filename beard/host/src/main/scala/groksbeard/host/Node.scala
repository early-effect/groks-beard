package groksbeard.host

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait NodeSocket extends js.Object:
  def write(data: String): Boolean                                       = js.native
  def end(): Unit                                                        = js.native
  def destroy(): Unit                                                    = js.native
  def setEncoding(enc: String): NodeSocket                               = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): NodeSocket = js.native

@js.native
trait NodeServer extends js.Object:
  def listen(path: String, cb: js.Function0[Any]): NodeServer             = js.native
  def close(cb: js.UndefOr[js.Function0[Any]] = js.undefined): NodeServer = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): NodeServer  = js.native

@js.native
@JSImport("net", JSImport.Namespace)
object nodeNet extends js.Object:
  def createServer(listener: js.Function1[NodeSocket, Any]): NodeServer = js.native

@js.native
@JSImport("fs", JSImport.Namespace)
object nodeFs extends js.Object:
  def mkdirSync(path: String, options: js.Dynamic): Unit           = js.native
  def unlinkSync(path: String): Unit                               = js.native
  def chmodSync(path: String, mode: Int): Unit                     = js.native
  def existsSync(path: String): Boolean                            = js.native
  def readFileSync(path: String, enc: String): String              = js.native
  def writeFileSync(path: String, data: String, enc: String): Unit = js.native

@js.native
trait NodeHash extends js.Object:
  def update(data: String): NodeHash   = js.native
  def digest(encoding: String): String = js.native

@js.native
@JSImport("crypto", JSImport.Namespace)
object nodeCrypto extends js.Object:
  def createHash(algorithm: String): NodeHash = js.native

@js.native
@JSImport("process", JSImport.Namespace)
object nodeProcess extends js.Object:
  def env: js.Dictionary[js.UndefOr[String]] = js.native
  def platform: String                       = js.native

@js.native
@JSImport("os", JSImport.Namespace)
object nodeOs extends js.Object:
  def tmpdir(): String = js.native

@js.native
@JSImport("path", JSImport.Namespace)
object nodePath extends js.Object:
  def dirname(p: String): String = js.native

@js.native
trait NodeWritable extends js.Object:
  def write(data: String): Boolean = js.native
  def end(): Unit                  = js.native

@js.native
trait NodeReadable extends js.Object:
  def setEncoding(enc: String): NodeReadable                               = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): NodeReadable = js.native

@js.native
trait ChildProcessHandle extends js.Object:
  def stdin: NodeWritable                                                        = js.native
  def stdout: NodeReadable                                                       = js.native
  def stderr: NodeReadable                                                       = js.native
  def kill(): Boolean                                                            = js.native
  def on(event: String, listener: js.Function1[js.Any, Any]): ChildProcessHandle = js.native

@js.native
@JSImport("child_process", JSImport.Namespace)
object nodeChildProcess extends js.Object:
  def spawn(command: String, args: js.Array[String], options: js.Dynamic): ChildProcessHandle =
    js.native
