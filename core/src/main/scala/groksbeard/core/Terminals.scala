package groksbeard.core

import zio.*
import zio.json.*

final class TerminalSeq:
  private val lock       = new Object
  private var n          = 0
  def next(): TerminalId =
    lock.synchronized {
      n += 1
      TerminalId.mint(n)
    }

final case class EnvVar(name: String, value: String) derives JsonCodec

final case class TerminalCreateParams(
    sessionId: SessionId = SessionId.empty,
    command: String,
    args: List[String] = Nil,
    env: List[EnvVar] = Nil,
    cwd: Option[String] = None,
    outputByteLimit: Option[Int] = None,
) derives JsonCodec

final case class TerminalCreateResult(terminalId: TerminalId) derives JsonCodec

final case class TerminalIdParams(sessionId: SessionId = SessionId.empty, terminalId: TerminalId) derives JsonCodec

final case class TerminalExitStatus(exitCode: Option[Int] = None, signal: Option[String] = None) derives JsonCodec

final case class TerminalOutputResult(
    output: String,
    truncated: Boolean,
    exitStatus: Option[TerminalExitStatus] = None,
) derives JsonCodec

object PlanTerminals:
  /** Measured 2026-09-06 against grok 1.0.13 (5e9a58528b76).
    *
    * Probe spawn advertised `clientCapabilities.terminal = true`, set mode to `plan`, and prompted for
    * `echo beard-probe > mutated.txt`. The agent did not send `terminal/create`. It edited session `plan.md` and called
    * `_x.ai/exit_plan_mode`. No handler-side mutating-shell allowlist.
    */
  val MutatingCreateInPlan: Boolean = false

  val DefaultByteLimit: Int = 1_048_576
end PlanTerminals

trait Terminals:
  def create(
      command: String,
      args: List[String],
      cwd: Option[String],
      env: List[EnvVar],
      limit: Option[Int],
  ): BeardError.Result[TerminalId]
  def output(id: TerminalId): UIO[Option[TerminalOutputResult]]
  def waitForExit(id: TerminalId): UIO[Option[TerminalExitStatus]]
  def kill(id: TerminalId): UIO[Boolean]
  def release(id: TerminalId): UIO[Boolean]
end Terminals

object Terminals:
  def capTail(text: String, limit: Int): (String, Boolean) =
    val kept = Utf8.keepTailToByteCap(text, limit)
    (kept, kept != text)

  def argv(command: String, args: List[String], host: String = TerminalShell.hostShell()): List[String] =
    TerminalShell.argv(command, args, host)

  def test(
      onCreate: (String, List[String]) => String = (cmd, args) => s"$cmd ${args.mkString(" ")}".trim,
      stdout: String = "ok\n",
      exit: TerminalExitStatus = TerminalExitStatus(Some(0), None),
  ): ULayer[Terminals] =
    ZLayer.succeed(new Terminals:
      private val ids  = TerminalSeq()
      private val lock = new Object
      private var out  = Map.empty[TerminalId, String]
      private var gone = Set.empty[TerminalId]
      def create(
          command: String,
          args: List[String],
          cwd: Option[String],
          env: List[EnvVar],
          limit: Option[Int],
      ): BeardError.Result[TerminalId] =
        ZIO.succeed {
          lock.synchronized {
            val id  = ids.next()
            val raw = onCreate(command, args)
            val cap = limit.filter(_ > 0).getOrElse(PlanTerminals.DefaultByteLimit)
            out += id -> Terminals.capTail(raw + stdout, cap)._1
            id
          }
        }
      def output(id: TerminalId): UIO[Option[TerminalOutputResult]] =
        ZIO.succeed {
          lock.synchronized {
            out.get(id).map { text =>
              TerminalOutputResult(text, truncated = false, exitStatus = Some(exit))
            }
          }
        }
      def waitForExit(id: TerminalId): UIO[Option[TerminalExitStatus]] =
        ZIO.succeed {
          lock.synchronized {
            if out.contains(id) || gone.contains(id) then Some(exit) else None
          }
        }
      def kill(id: TerminalId): UIO[Boolean] =
        ZIO.succeed {
          lock.synchronized {
            out.contains(id)
          }
        }
      def release(id: TerminalId): UIO[Boolean] =
        ZIO.succeed {
          lock.synchronized {
            val had = out.contains(id)
            out -= id
            if had then gone += id
            had
          }
        })
end Terminals
