package groksbeard.core

object HostDispatch:
  def apply(runtime: ChatRuntime, msg: WebviewMsg, extra: HostMsg => Unit): Unit =
    msg match
      case WebviewMsg.Ready                            => runtime.ready()
      case WebviewMsg.Send(text)                       => runtime.send(text)
      case WebviewMsg.Queue(text)                      => runtime.queue(text)
      case WebviewMsg.Cancel                           => runtime.cancel()
      case WebviewMsg.SetMode(id)                      => runtime.setMode(id)
      case WebviewMsg.MentionQuery(q)                  => runtime.mentionQuery(q)
      case WebviewMsg.MentionPick(path, absPath)       => runtime.mentionPick(path, absPath)
      case WebviewMsg.AddSelection                     => ()
      case WebviewMsg.RemoveChip(absPath, start, end)  => runtime.removeChip(absPath, start, end)
      case WebviewMsg.SetSetting(key, value)           => runtime.setSetting(key, value)
      case WebviewMsg.OpenSettings                     => extra(HostMsg.settings(runtime.currentSettings))
      case WebviewMsg.OpenDiff(id)                     => runtime.openDiff(id)
      case WebviewMsg.OpenChanges                      => runtime.openChanges()
      case WebviewMsg.KeepChange(path)                 => runtime.keep(path)
      case WebviewMsg.UndoChange(path)                 => runtime.undo(path)
      case WebviewMsg.CloseDiff                        => runtime.closeDiff()
      case WebviewMsg.PermissionChoice(requestId, opt) => runtime.permissionChoice(requestId, opt)
      case WebviewMsg.PermissionPark(_)                => ()
      case WebviewMsg.PlanVerdict(requestId, verdict)  => runtime.planVerdict(requestId, verdict)
      case WebviewMsg.QuestionChoice(id, qid, oid)     => runtime.questionChoice(id, qid, oid)
      case WebviewMsg.QuestionDismiss(id)              => runtime.questionDismiss(id)
      case WebviewMsg.ElicitAccept(id)                 => runtime.elicitAccept(id)
      case WebviewMsg.ElicitDecline(id)                => runtime.elicitDecline(id)
      case _                                           => ()
end HostDispatch
