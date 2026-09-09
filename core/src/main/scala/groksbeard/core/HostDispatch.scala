package groksbeard.core

import zio.*

object HostDispatch:
  def apply(runtime: ChatRuntime, msg: WebviewMsg, extra: HostMsg => UIO[Unit]): UIO[Unit] =
    msg match
      case WebviewMsg.Ready                          => runtime.ready
      case WebviewMsg.Send(text, images)             => runtime.send(text, images)
      case WebviewMsg.Queue(text, images)            => runtime.queue(text, images)
      case WebviewMsg.QueueSendNow(id)               => runtime.sendNow(id)
      case WebviewMsg.QueueDrop(id)                  => runtime.dropQueued(id)
      case WebviewMsg.StopTask(id)                   => runtime.stopTask(id)
      case WebviewMsg.Cancel                         => runtime.cancel
      case WebviewMsg.CancelTurnChoice(_, keep)      => runtime.cancelTurn(keep)
      case WebviewMsg.SetMode(id)                    => runtime.setMode(id)
      case WebviewMsg.SetModel(id, effort)           => runtime.setModel(id, Option(effort).filter(_.nonEmpty))
      case WebviewMsg.SetEffort(level)               => runtime.setEffort(level)
      case WebviewMsg.CycleMode                      => runtime.cycleMode
      case WebviewMsg.SlashPick(name)                => runtime.slashPick(name)
      case WebviewMsg.Fork(worktree, directive)      => runtime.forkSession(worktree, directive)
      case WebviewMsg.NewSession                     => runtime.newSession
      case WebviewMsg.ResumeSession(id, restore)     => runtime.resumeSession(id, restore)
      case WebviewMsg.OpenSessionPicker              => runtime.openPicker
      case WebviewMsg.CloseSessionPicker             => runtime.closePicker
      case WebviewMsg.RenameSession(id, title, auto) =>
        runtime.renameSession(id, if auto then RenameOp.Auto else RenameOp.Manual(title))
      case WebviewMsg.DeleteSession(id)                 => runtime.deleteSession(id)
      case WebviewMsg.CopyOut(text, path, backup, conv) =>
        runtime.copyOut(text, path, backup, conv)
      case WebviewMsg.MentionQuery(q)                 => runtime.mentionQuery(q)
      case WebviewMsg.MentionPick(path, absPath)      => runtime.mentionPick(path, absPath)
      case WebviewMsg.AddSelection                    => ZIO.unit
      case WebviewMsg.RemoveChip(absPath, start, end) => runtime.removeChip(absPath, start, end)
      case WebviewMsg.SetSetting(key, value)          => runtime.setSetting(key, value)
      case WebviewMsg.OpenSettings                    =>
        runtime.currentSettings.flatMap(s => extra(HostMsg.settings(s)))
      case WebviewMsg.OpenDiff(id)                     => runtime.openDiff(id)
      case WebviewMsg.OpenFile(path, line)             => runtime.openFile(path, line)
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
      case WebviewMsg.AttachChild(id)                  => runtime.attachChild(id)
      case WebviewMsg.DetachChild                      => ZIO.unit
      case WebviewMsg.SteerChild(id, text, queue)      => runtime.steerChild(id, text, queue)
      case WebviewMsg.ViewPlan                         => runtime.viewPlan
      case WebviewMsg.OpenAgents                       => runtime.openAgents
      case WebviewMsg.OpenDashboard                    => runtime.openDashboard
      case WebviewMsg.OpenWorkflows                    => runtime.openWorkflows
      case WebviewMsg.OpenDoctor                       => runtime.openDoctor
      case WebviewMsg.OpenTheme                        => ZIO.unit
      case WebviewMsg.Btw(text)                        => runtime.btw(text)
      case WebviewMsg.SetTheme(id)                     => runtime.setTheme(id)
      case WebviewMsg.ToggleCompact                    => runtime.toggleCompact
      case WebviewMsg.ToggleVim                        => runtime.toggleVim
      case WebviewMsg.AddImage(mime, data, name)       => runtime.addImage(mime, data, name)
      case WebviewMsg.RemoveImage(id)                  => runtime.removeImage(id)
      case WebviewMsg.PersistConfig(table, key, value) => runtime.persistConfig(table, key, value)
end HostDispatch
