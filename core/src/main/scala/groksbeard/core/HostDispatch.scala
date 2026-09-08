package groksbeard.core

import zio.*

object HostDispatch:
  def apply(runtime: ChatRuntime, msg: WebviewMsg, extra: HostMsg => UIO[Unit]): UIO[Unit] =
    msg match
      case WebviewMsg.Ready                          => runtime.ready
      case WebviewMsg.Send(text)                     => runtime.send(text)
      case WebviewMsg.Queue(text)                    => runtime.queue(text)
      case WebviewMsg.QueueSendNow(id)               => runtime.sendNow(id)
      case WebviewMsg.QueueDrop(id)                  => runtime.dropQueued(id)
      case WebviewMsg.StopTask(id)                   => runtime.stopTask(id)
      case WebviewMsg.Cancel                         => runtime.cancel
      case WebviewMsg.SetMode(id)                    => runtime.setMode(id)
      case WebviewMsg.SetModel(id, effort)           => runtime.setModel(id, Option(effort).filter(_.nonEmpty))
      case WebviewMsg.SetEffort(level)               => runtime.setEffort(level)
      case WebviewMsg.CycleMode                      => runtime.cycleMode
      case WebviewMsg.SlashPick(name)                => runtime.slashPick(name)
      case WebviewMsg.Fork(worktree, directive)      => runtime.forkSession(worktree, directive)
      case WebviewMsg.NewSession                     => runtime.newSession
      case WebviewMsg.ResumeSession(id)              => runtime.resumeSession(id)
      case WebviewMsg.OpenSessionPicker              => runtime.openPicker
      case WebviewMsg.CloseSessionPicker             => runtime.closePicker
      case WebviewMsg.RenameSession(id, title, auto) =>
        runtime.renameSession(id, if auto then RenameOp.Auto else RenameOp.Manual(title))
      case WebviewMsg.DeleteSession(id)                 => runtime.deleteSession(id)
      case WebviewMsg.CopyOut(text, path, backup, conv) =>
        runtime.copyOut(text, path, backup, conv)
      case WebviewMsg.MentionQuery(q)                  => runtime.mentionQuery(q)
      case WebviewMsg.MentionPick(path, absPath)       => runtime.mentionPick(path, absPath)
      case WebviewMsg.AddSelection                     => ZIO.unit
      case WebviewMsg.RemoveChip(absPath, start, end)  => runtime.removeChip(absPath, start, end)
      case WebviewMsg.SetSetting(key, value)           => runtime.setSetting(key, value)
      case WebviewMsg.OpenSettings                     => extra(HostMsg.settings(runtime.currentSettings))
      case WebviewMsg.OpenDiff(id)                     => runtime.openDiff(id)
      case WebviewMsg.OpenChanges                      => runtime.openChanges
      case WebviewMsg.KeepChange(path)                 => runtime.keep(path)
      case WebviewMsg.UndoChange(path)                 => runtime.undo(path)
      case WebviewMsg.KeepTurn(turnId)                 => runtime.keepTurn(turnId)
      case WebviewMsg.UndoTurn(turnId)                 => runtime.undoTurn(turnId)
      case WebviewMsg.KeepAll                          => runtime.keepAll
      case WebviewMsg.UndoAll                          => runtime.undoAll
      case WebviewMsg.CloseDiff                        => runtime.closeDiff
      case WebviewMsg.PermissionChoice(requestId, opt) => runtime.permissionChoice(requestId, opt)
      case WebviewMsg.PermissionPark(_)                => ZIO.unit
      case WebviewMsg.PlanVerdict(requestId, verdict)  => runtime.planVerdict(requestId, verdict)
      case WebviewMsg.QuestionSubmit(id, answers)      => runtime.questionSubmit(id, answers)
      case WebviewMsg.QuestionDismiss(id)              => runtime.questionDismiss(id)
      case WebviewMsg.ElicitAccept(id)                 => runtime.elicitAccept(id)
      case WebviewMsg.ElicitDecline(id)                => runtime.elicitDecline(id)
      case WebviewMsg.OpenRewind                       => runtime.openRewind
      case WebviewMsg.CloseRewind                      => runtime.closeRewind
      case WebviewMsg.RewindTo(index)                  => runtime.rewindTo(index)
      case WebviewMsg.ListMcps                         => runtime.listMcps
      case WebviewMsg.SetMcpEnabled(name, enabled)     => runtime.setMcpEnabled(name, enabled)
      case WebviewMsg.Log(message, level)              =>
        level.toLowerCase match
          case "warn" | "warning" => ZIO.logWarning(message)
          case "info"             => ZIO.logInfo(message)
          case "debug"            => ZIO.logDebug(message)
          case _                  => ZIO.logError(message)
end HostDispatch
